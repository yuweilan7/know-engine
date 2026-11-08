package know.engine.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import know.engine.ai.prompt.PromptService;
import know.engine.document.service.FileStorageService;
import know.engine.document.service.impl.MinerUFileProcessServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MinerUImageDescriptionTest {
    @Test
    void sendsInlineImagesAndKeepsTheDocumentWhenOneDescriptionFails() throws Exception {
        byte[] archive = archive();
        var received = new CopyOnWriteArrayList<String>();
        var json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file_parse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "application/zip", archive);
        });
        server.createContext("/chat/completions", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            String image = request.at("/messages/0/content/1/image_url/url").asText();
            received.add(image);
            if (image.endsWith("AQID")) {
                respond(exchange, 200, "application/json", """
                        {"id":"test","model":"qwen3-vl-plus","choices":[{"index":0,
                        "message":{"role":"assistant","content":"节点 A → 节点 B"},"finish_reason":"stop"}]}
                        """.getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, 400, "application/json", "{\"error\":{\"message\":\"Image rejected\"}}"
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var storage = mock(FileStorageService.class);
            when(storage.uploadFile(anyString(), any(byte[].class), anyString()))
                    .thenAnswer(call -> "http://127.0.0.1:19090/know-engine/" + call.<String>getArgument(0));
            var prompts = mock(PromptService.class);
            when(prompts.getPrompt(anyString())).thenReturn("Describe this image");
            var parser = new MinerUFileProcessServiceImpl();
            ReflectionTestUtils.setField(parser, "fileStorageService", storage);
            ReflectionTestUtils.setField(parser, "promptService", prompts);
            ReflectionTestUtils.setField(parser, "fileParseApiUrl", base);
            ReflectionTestUtils.setField(parser, "connectTimeout", 5_000);
            ReflectionTestUtils.setField(parser, "responseTimeout", 5_000);
            ReflectionTestUtils.setField(parser, "chatModelBaseUrl", base);
            ReflectionTestUtils.setField(parser, "chatModelApiKey", "test");

            String key = parser.processDocument(new ByteArrayInputStream(new byte[]{1}), "test.pdf", "parsed/");

            assertEquals(List.of("data:image/png;base64,AQID", "data:image/png;base64,BAUG"), received);
            assertEquals("parsed/document.md", key);
            var output = ArgumentCaptor.forClass(byte[].class);
            verify(storage).uploadFile(eq(key), output.capture(), eq("text/markdown"));
            String markdown = new String(output.getValue(), StandardCharsets.UTF_8);
            assertTrue(markdown.contains("## 系统拓扑"));
            assertTrue(markdown.contains("![节点 A → 节点 B](http://127.0.0.1:19090/know-engine/parsed/images/a.png)"));
            assertTrue(markdown.contains("![原说明](http://127.0.0.1:19090/know-engine/parsed/images/b.png)"));
        } finally {
            server.stop(0);
        }
    }

    private static byte[] archive() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : Map.of(
                    "test.md", "## 系统拓扑\n\n![](images/a.png)\n\n![原说明](images/b.png)\n".getBytes(StandardCharsets.UTF_8),
                    "images/a.png", new byte[]{1, 2, 3}, "images/b.png", new byte[]{4, 5, 6}).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
