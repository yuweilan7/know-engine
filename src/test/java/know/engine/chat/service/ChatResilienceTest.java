package know.engine.chat.service;

import com.alibaba.druid.pool.DruidDataSource;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import know.engine.ai.config.OnlineModelHttpClient;
import know.engine.ai.prompt.PromptService;
import know.engine.chat.constant.RetrievalSource;
import know.engine.document.service.DocumentService;
import know.engine.document.service.SegmentService;
import know.engine.rag.ElasticsearchRagRetriever;
import know.engine.rag.RagContext;
import know.engine.rag.reranker.DashScopeScoringModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import reactor.core.Exceptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatResilienceTest {
    private final OnlineChatWork work = new OnlineChatWork(mock(javax.sql.DataSource.class));
    private final ElasticsearchRagRetriever search = mock(ElasticsearchRagRetriever.class);
    private final OpenAiEmbeddingModel embedding = mock(OpenAiEmbeddingModel.class);
    private final DashScopeScoringModel rerank = mock(DashScopeScoringModel.class);
    private final StreamingChatModel model = mock(StreamingChatModel.class, CALLS_REAL_METHODS);

    @AfterEach
    void closeWorkers() {
        work.shutdown();
    }

    @Test
    void keywordEvidenceSurvivesAnEmbeddingTimeoutAndRerankFailure() throws Exception {
        var cancelled = new CountDownLatch(1);
        when(embedding.embed(anyString())).thenAnswer(ignored -> {
            try {
                new CountDownLatch(1).await();
                throw new AssertionError();
            } catch (InterruptedException error) {
                cancelled.countDown();
                throw new IllegalStateException(error);
            }
        });
        when(search.retrieve("question", List.of(7L), null)).thenReturn(List.of(
                new RagContext.Hit(new RagContext.ChunkKey(1, 7, "a"), "a", "keyword evidence", null, null, null, 1, 2.0),
                new RagContext.Hit(new RagContext.ChunkKey(1, 7, "b"), "b", "alternate evidence", null, null, null, 2, 1.0)));
        when(rerank.scoreAll(anyList(), anyString())).thenThrow(new IllegalStateException("rate limited"));
        doAnswer(call -> {
            var request = call.<ChatRequest>getArgument(0);
            assertTrue(request.messages().getLast().toString().contains("keyword evidence"));
            var handler = call.<StreamingChatResponseHandler>getArgument(1);
            handler.onPartialResponse("answer");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("answer")).build());
            return null;
        }).when(model).doChat(any(), any());

        var result = infer();
        assertEquals("COMPLETED", result.status());
        assertEquals("answer", result.answer());
        assertEquals(List.of("EMBEDDING_UNAVAILABLE", "RERANK_UNAVAILABLE"), result.degradedStages());
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        verify(embedding, times(1)).embed("question");
        verify(rerank, times(1)).scoreAll(anyList(), eq("question"));
        assertEquals(2, result.context().references().size());
        assertTrue(result.context().references().stream().allMatch(ref -> ref.getRetrievalSource() == RetrievalSource.KEYWORD));
    }

    @Test
    void totalSearchFailureEndsTheRoundWithoutSendingAQuestionToTheGenerator() throws Exception {
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("rate limited"));
        when(search.retrieve("question", List.of(7L), null)).thenThrow(new IllegalStateException("search unavailable"));

        var result = infer();
        assertEquals("FAILED", result.status());
        assertEquals("RETRIEVAL_UNAVAILABLE", result.reasonCode());
        verifyNoInteractions(model);
    }

    @Test
    void keywordSuccessWithNoHitsAndVectorFailureUsesALocalAnswer() throws Exception {
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("rate limited"));
        when(search.retrieve("question", List.of(7L), null)).thenReturn(List.of());

        var result = infer();
        assertEquals("COMPLETED", result.status());
        assertEquals("NO_EVIDENCE", result.reasonCode());
        assertTrue(result.context().references().isEmpty());
        assertTrue(result.answer().contains("资料不足"));
        verifyNoInteractions(model);
    }

    @Test
    void firstAnswerTimeoutClosesTheModelConnectionBeforeHeadersArrive() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var transport = new OnlineModelHttpClient();
        try (var server = new ServerSocket(0, 1, loopback)) {
            server.setSoTimeout(4000);
            var peerClosed = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept();
                     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    socket.setSoTimeout(4000);
                    String header;
                    int length = 0;
                    while ((header = reader.readLine()) != null && !header.isEmpty()) {
                        if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(header.substring(15).trim());
                        }
                    }
                    if (length <= 0) return false;
                    for (int i = 0; i < length; i++) {
                        if (reader.read() == -1) return false;
                    }
                    try {
                        return reader.read() == -1;
                    } catch (SocketException reset) {
                        // Cancelling an Apache request may send a TCP reset instead of a FIN.
                        return true;
                    }
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var upstream = OpenAiStreamingChatModel.builder().modelName("test").apiKey("test")
                    .baseUrl("http://127.0.0.1:" + server.getLocalPort() + "/v1")
                    .httpClientBuilder(transport.builder()).timeout(Duration.ofSeconds(8)).build();

            var failure = assertThrows(RuntimeException.class, () -> work.<String>stream(Duration.ofSeconds(5),
                            Duration.ofSeconds(1), Duration.ofSeconds(4),
                            sink -> upstream.chat("hello", new StreamingChatResponseHandler() {
                                @Override public void onCompleteResponse(ChatResponse response) { }
                                @Override public void onError(Throwable error) { sink.error(error); }
                            }))
                    .collectList()
                    .block(Duration.ofSeconds(4)));
            assertInstanceOf(TimeoutException.class, Exceptions.unwrap(failure));
            assertTrue(peerClosed.get(3, TimeUnit.SECONDS));
        } finally {
            transport.destroy();
        }
    }

    @Test
    void cancellingTheSubscriberDoesNotFreeAnOccupiedWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var first = work.run(OnlineChatWork.Stage.TITLE, Duration.ofSeconds(2), () -> {
            entered.countDown();
            release.join(); // Simulates a client which ignores interruption.
            return "finished";
        }).toFuture();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            first.cancel(true);
            var queued = work.run(OnlineChatWork.Stage.TITLE, Duration.ofSeconds(2), () -> "next").toFuture();
            assertFalse(queued.isDone());
            assertThrows(OnlineChatWork.Busy.class, () ->
                    work.run(OnlineChatWork.Stage.TITLE, Duration.ofSeconds(1), () -> "overflow").block());
            release.complete(null);
            assertEquals("next", queued.get(1, TimeUnit.SECONDS));
        } finally {
            release.complete(null);
        }
    }

    @Test
    void databaseWaitSettingsBindToDruid() throws Exception {
        var environment = new StandardEnvironment();
        var properties = new YamlPropertySourceLoader().load("test", new ClassPathResource("application.yml"));
        properties.forEach(environment.getPropertySources()::addFirst);
        try (var dataSource = new DruidDataSource()) {
            Binder.get(environment).bind("spring.datasource.druid", Bindable.ofInstance(dataSource));
            assertEquals(400, dataSource.getMaxWait());
            assertEquals(1500, dataSource.getConnectTimeout());
            assertEquals(2500, dataSource.getSocketTimeout());
            assertEquals(2, dataSource.getQueryTimeout());
            assertTrue(dataSource.isFailFast());
        }
    }

    private ChatService.GenerationResult infer() {
        var chat = mock(ChatModel.class);
        var documents = mock(DocumentService.class);
        when(documents.activeVersionIds()).thenReturn(List.of(7L));
        var prompts = new PromptService();
        var settings = new ChatSettings();
        var retrieval = new RagRetrievalService(chat, embedding, documents, mock(SegmentService.class), search,
                rerank, prompts, work, settings);
        var service = new ChatService(chat, new ChatModelGateway(model, model, work, settings),
                retrieval, prompts, work, settings);
        var result = service.evaluate(new ChatService.Request("question", ChatService.Mode.RAG), List.of())
                .block(Duration.ofSeconds(8));
        verify(documents, times(1)).activeVersionIds();
        return result;
    }
}
