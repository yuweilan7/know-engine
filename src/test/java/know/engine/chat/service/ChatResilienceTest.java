package know.engine.chat.service;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.transport.TransportOptions;
import com.alibaba.druid.pool.DruidDataSource;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import know.engine.ai.config.OnlineModelHttpClient;
import know.engine.ai.prompt.PromptService;
import know.engine.ai.service.TitleSummaryService;
import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.constant.RetrievalSource;
import know.engine.chat.memory.DatabaseChatMemoryStore;
import know.engine.document.service.DocumentService;
import know.engine.document.service.SegmentService;
import know.engine.rag.reranker.DashScopeScoringModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.Exceptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatResilienceTest {
    private final OnlineChatWork work = new OnlineChatWork(mock(javax.sql.DataSource.class));
    private final ElasticsearchAsyncClient search = mock(ElasticsearchAsyncClient.class);
    private final OpenAiEmbeddingModel embedding = mock(OpenAiEmbeddingModel.class);
    private final DashScopeScoringModel rerank = mock(DashScopeScoringModel.class);
    private final ChatMessageService messages = mock(ChatMessageService.class);
    private final DatabaseChatMemoryStore memory = mock(DatabaseChatMemoryStore.class);
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
        var response = keywordHits();
        when(search.search(any(SearchRequest.class), eq(Document.class)))
                .thenAnswer(call -> {
                    assertNotNull(call.<SearchRequest>getArgument(0).query().bool());
                    return CompletableFuture.completedFuture(response);
                });
        when(rerank.scoreAll(anyList(), anyString())).thenThrow(new IllegalStateException("rate limited"));
        doAnswer(call -> {
            var request = call.<ChatRequest>getArgument(0);
            assertTrue(request.messages().getLast().toString().contains("keyword evidence"));
            var handler = call.<StreamingChatResponseHandler>getArgument(1);
            handler.onPartialResponse("answer");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("answer")).build());
            return null;
        }).when(model).doChat(any(), any());

        var events = service().chat("user", "question", "conversation").collectList().block(Duration.ofSeconds(8));

        assertNotNull(events);
        assertEquals("[DONE]:conversation", events.getLast());
        assertTrue(events.contains("answer"));
        assertTrue(events.stream().anyMatch(event -> event.startsWith("[REFERENCE]:") && event.contains("keyword evidence")),
                events::toString);
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        verify(embedding, times(1)).embed("question");
        verify(rerank, times(1)).scoreAll(anyList(), eq("question"));
        verify(memory, times(1)).loadHistory("conversation", "user-message");
        verifyNoMoreInteractions(memory);
        verify(messages).updateContent(eq("assistant-message"), eq("answer"), any());
        verify(messages).updateRagReferences(eq("assistant-message"), argThat(refs ->
                refs.size() == 2 && refs.stream().allMatch(ref -> ref.getRetrievalSource() == RetrievalSource.KEYWORD)));
    }

    @Test
    void totalSearchFailureEndsTheRoundWithoutSendingAQuestionToTheGenerator() {
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("rate limited"));
        when(search.search(any(SearchRequest.class), eq(Document.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("search unavailable")));

        var events = service().chat("user", "question", "conversation").collectList().block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.getLast().startsWith("[WARN]:知识检索暂不可用"));
        verifyNoInteractions(model);
        verify(messages, never()).updateContent(anyString(), anyString(), any());
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

    private ChatService service() {
        var chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"related\":true,\"intent\":\"knowledge\",\"reasoning\":\"test\"}")).build());
        var documents = mock(DocumentService.class);
        when(documents.activeVersionIds()).thenReturn(List.of(7L));
        when(search.withTransportOptions(any(TransportOptions.class))).thenReturn(search);
        when(messages.saveMessage("conversation", ChatMessageType.USER, "question")).thenReturn("user-message");
        when(messages.saveMessage("conversation", ChatMessageType.ASSISTANT, null)).thenReturn("assistant-message");
        when(memory.loadHistory("conversation", "user-message")).thenReturn(List.of());
        var transaction = mock(PlatformTransactionManager.class);
        when(transaction.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        return new ChatService(chat, model, model, mock(TitleSummaryService.class), mock(ChatConversationService.class),
                messages, memory, mock(SegmentService.class), documents, search, embedding, new PromptService(), work,
                new TransactionTemplate(transaction), rerank, 8, Duration.ofSeconds(5), Duration.ofSeconds(6),
                Duration.ofSeconds(2), Duration.ofSeconds(10), true, true);
    }

    private SearchResponse<Document> keywordHits() {
        var first = hit("a", "keyword evidence", 2);
        var second = hit("b", "alternate evidence", 1);
        return SearchResponse.of(r -> r.took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(first, second)));
    }

    private Hit<Document> hit(String id, String text, double score) {
        var document = mock(Document.class);
        when(document.getText()).thenReturn(text);
        when(document.getMetadata()).thenReturn(Map.of("docId", 1L, "versionId", 7L, "chunkId", id));
        return Hit.of(h -> h.index("knowledge").id(id).score(score).source(document));
    }
}
