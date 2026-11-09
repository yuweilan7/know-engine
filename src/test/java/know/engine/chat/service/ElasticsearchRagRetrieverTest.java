package know.engine.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import know.engine.rag.ElasticsearchRagRetriever;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ElasticsearchRagRetrieverTest {
    @Test
    void bothRequestsContainTheSameVersionFilterAndReturnTypedMetadata() throws Exception {
        var json = new ObjectMapper();
        var calls = new java.util.concurrent.LinkedBlockingQueue<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/know-engine-chunks/_search", exchange -> {
            try (exchange) {
                calls.add(json.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"timed_out":false,"_shards":{"failed":0},"hits":{"hits":[
                          {"_id":"chunk","_score":2.5,"_source":{"text":"evidence","metadata":
                            {"docId":123,"versionId":7,"chunkId":"chunk","parentChunkId":"parent"}}}]}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (var client = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            var search = new ElasticsearchRagRetriever(client, json);
            assertEquals("parent", search.retrieve("question", List.of(7L), null).getFirst().parentId());
            assertEquals(2.5, search.retrieve("question", List.of(7L), List.of(0.8f)).getFirst().score());
            var keyword = calls.take();
            var vector = calls.take();
            assertEquals(keyword.at("/query/bool/filter/terms/metadata.versionId"),
                    vector.at("/query/knn/filter/terms/metadata.versionId"));
            assertEquals(7, keyword.at("/query/bool/filter/terms/metadata.versionId/0").asInt());
            assertEquals(0.5, vector.path("min_score").asDouble());
            assertThrows(IllegalStateException.class, () -> search.retrieve("question", List.of(8L), null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellingSearchClosesTheUnderlyingHttpConnectionBeforeHeaders() throws Exception {
        var work = new OnlineChatWork(mock(javax.sql.DataSource.class));
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var client = RestClient.builder(new HttpHost("127.0.0.1", server.getLocalPort())).build()) {
            server.setSoTimeout(4000);
            var received = new CountDownLatch(1);
            var peerClosed = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept();
                     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    socket.setSoTimeout(3500);
                    String line;
                    int length = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    for (int i = 0; i < length; i++) assertNotEquals(-1, reader.read());
                    received.countDown();
                    try {
                        return reader.read() == -1;
                    } catch (SocketException reset) {
                        return true;
                    }
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var search = new ElasticsearchRagRetriever(client, new ObjectMapper());
            var result = work.run(OnlineChatWork.Stage.SEARCH, Duration.ofSeconds(4),
                    () -> search.retrieve("question", List.of(7L), null)).toFuture();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertTrue(peerClosed.get(3, TimeUnit.SECONDS));
        } finally {
            work.shutdown();
        }
    }
}
