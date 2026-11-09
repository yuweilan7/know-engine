package know.engine.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.FinishReason;
import know.engine.ai.prompt.PromptService;
import know.engine.ai.service.TitleSummaryService;
import know.engine.chat.controller.ChatController;
import know.engine.chat.controller.ChatConversationController;
import know.engine.chat.controller.ChatMessageController;
import know.engine.chat.memory.DatabaseChatMemoryStore;
import know.engine.chat.service.impl.ChatConversationServiceImpl;
import know.engine.chat.service.impl.ChatMessageServiceImpl;
import know.engine.config.ChatMvcConfig;
import know.engine.config.MyMetaObjectHandler;
import know.engine.config.MybatisPlusConfig;
import know.engine.document.service.DocumentService;
import know.engine.document.service.SegmentService;
import know.engine.rag.ElasticsearchRagRetriever;
import know.engine.rag.reranker.DashScopeScoringModel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "CHAT_IT_JDBC", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ke_chat_test_[a-z0-9]+\\?.+")
class ChatRunIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final String DB_USER = System.getenv("CHAT_IT_USER");
    private static final String DB_PASSWORD = System.getenv("CHAT_IT_PASSWORD");
    private static ServletWebServerApplicationContext app;
    private static String base;
    private static JdbcTemplate db;
    private static FakeModel model;

    @BeforeAll
    static void start() {
        String url = System.getenv("CHAT_IT_JDBC");
        var source = new DriverManagerDataSource(url, DB_USER, DB_PASSWORD);
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(source);
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(TestApplication.class)
                .properties(Map.of("spring.config.location", "optional:classpath:/chat-integration.yml",
                        "spring.datasource.url", url, "spring.datasource.username", DB_USER, "spring.datasource.password", DB_PASSWORD,
                        "server.address", "127.0.0.1", "server.port", "0", "spring.jmx.enabled", "false",
                        "know-engine.chat.heartbeat-interval", "250ms", "know-engine.chat.max-concurrent", "2"))
                .run();
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        model = app.getBean(FakeModel.class);
        db = app.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() throws Exception {
        if (app == null) return;
        // Opt-in window for testing the real page against the same isolated MySQL and fixture model.
        String marker = System.getenv("CHAT_IT_BROWSER_MARKER");
        if (marker != null) {
            Path path = Path.of(marker);
            Files.writeString(path, base);
            System.out.println("CHAT_BROWSER_READY " + base);
            long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(250);
        }
        app.close();
    }

    @Test
    void identicalConcurrentCreateUsesOneRunAndOnePairOfMessages() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = JSON.writeValueAsString(Map.of("content", "idempotent", "clientRequestId", key, "mode", "COMMON"));
        var first = HTTP.sendAsync(request("/chat/runs", "POST", body), HttpResponse.BodyHandlers.ofString());
        var second = HTTP.sendAsync(request("/chat/runs", "POST", body), HttpResponse.BodyHandlers.ofString());
        var a = first.get(12, TimeUnit.SECONDS);
        var b = second.get(12, TimeUnit.SECONDS);
        assertEquals(200, a.statusCode(), a.body());
        assertEquals(200, b.statusCode(), b.body());
        var run = JSON.readTree(a.body());
        assertEquals(run, JSON.readTree(b.body()));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM chat_message WHERE run_id = ?", Integer.class,
                run.path("runId").asText()));
        assertEquals(409, call("/chat/runs", "POST", body.replace("idempotent", "different")).statusCode());
        assertEquals(404, call(runPath(run) + "?userId=someone-else", "GET", null).statusCode());
        assertEquals(409, call("/chat/conversation/delete?conversationId=" + run.path("conversationId").asText(),
                "DELETE", null).statusCode());
        assertEquals("CANCELLED", JSON.readTree(call(runPath(run) + "/cancel", "POST", null).body()).path("status").asText());
        assertEquals(0, model.count("idempotent"));
        assertEquals(200, call("/chat/conversation/delete?conversationId=" + run.path("conversationId").asText(),
                "DELETE", null).statusCode());
        assertEquals(410, call("/chat/runs", "POST", body).statusCode());
        assertEquals(410, call(runPath(run), "GET", null).statusCode());
    }

    @Test
    void servletSseSendsNamedEventsAndSavedEnd() throws Exception {
        var run = create("normal", "AUTO");
        var response = call(runPath(run) + "/events", "GET", null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        String stream = response.body();
        assertTrue(stream.indexOf("event:meta") < stream.indexOf("event:references"), stream);
        assertTrue(stream.indexOf("event:references") < stream.indexOf("event:delta"), stream);
        assertTrue(stream.indexOf("event:delta") < stream.indexOf("event:end"), stream);
        assertTrue(stream.contains("\"persisted\":true"), stream);
        assertTrue(stream.contains("[WARN]:普通文本\\n第二行"), stream);
        assertEquals("COMPLETED", state(run).path("run").path("status").asText());
        assertEquals("[WARN]:普通文本\n第二行", state(run).path("answer").asText());
        assertEquals(409, call(runPath(run) + "/events", "GET", null).statusCode());
        assertEquals(1, model.count("normal"));
    }

    @Test
    void concurrentCreateWithDifferentKeysCanOpenOnlyOneRunPerConversation() throws Exception {
        var seed = create("seed", "COMMON");
        call(runPath(seed) + "/cancel", "POST", null);
        String conversation = seed.path("conversationId").asText();
        var requests = List.of("candidate-a", "candidate-b").stream().map(question -> {
            var body = JSON.createObjectNode().put("clientRequestId", UUID.randomUUID().toString())
                    .put("conversationId", conversation).put("content", question).put("mode", "COMMON");
            return HTTP.sendAsync(request("/chat/runs", "POST", body.toString()), HttpResponse.BodyHandlers.ofString());
        }).toList();
        var first = requests.getFirst().get(12, TimeUnit.SECONDS);
        var second = requests.getLast().get(12, TimeUnit.SECONDS);
        assertEquals(List.of(200, 409), java.util.stream.Stream.of(first.statusCode(), second.statusCode()).sorted().toList());
        var winner = JSON.readTree((first.statusCode() == 200 ? first : second).body());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM chat_message WHERE run_id = ?", Integer.class,
                winner.path("runId").asText()));
        call(runPath(winner) + "/cancel", "POST", null);
    }

    @Test
    void stalledSubscriberUsesBoundedBufferAndCannotOverwriteSavedResult() throws Exception {
        var created = create("BURST_slow", "COMMON");
        String runId = created.path("runId").asText();
        var runs = app.getBean(ChatRunService.class);
        var subscriber = new reactor.core.publisher.BaseSubscriber<ChatRunService.ChatEvent>() {
            @Override protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) { request(1); }
        };
        try {
            runs.open(runId, null).subscribe(subscriber);
            await(() -> state(created).path("run").path("status").asText().equals("FAILED"));
            var saved = state(created);
            assertEquals("SLOW_CONSUMER", saved.path("reasonCode").asText());
            assertTrue(saved.path("partial").asBoolean());
            var store = app.getBean(ChatRunStore.class);
            var run = store.find(runId);
            store.checkpoint(run, new ChatRunStore.Snapshot("stale", null, Long.MAX_VALUE, "GENERATION",
                    List.of(), null, null, null, null));
            assertEquals(saved.path("answer"), state(created).path("answer"));
        } finally {
            subscriber.cancel();
        }
    }

    @Test
    void stopAfterCheckpointPreservesPartialAndExcludesItFromMemory() throws Exception {
        var run = create("HOLD_partial", "COMMON");
        try (InputStream stream = open(run)) {
            await(() -> model.count("HOLD_partial") == 1);
            await(() -> state(run).path("partial").asBoolean());
            assertEquals(409, call(runPath(run) + "/events", "GET", null).statusCode());
            var stopped = JSON.readTree(call(runPath(run) + "/cancel", "POST", null).body());
            assertTrue(stopped.path("accepted").asBoolean(), stopped::toString);
            assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("event:end"));
        }
        var saved = state(run);
        assertEquals("CANCELLED", saved.path("run").path("status").asText());
        assertEquals("USER_CANCEL", saved.path("reasonCode").asText());
        assertTrue(saved.path("partial").asBoolean());
        assertEquals("已收到部分回答", saved.path("answer").asText());
        await(() -> model.interrupted.containsKey("HOLD_partial"));
        var next = create("next", "COMMON", run.path("conversationId").asText());
        var memory = app.getBean(DatabaseChatMemoryStore.class);
        assertTrue(memory.loadHistory(next.path("conversationId").asText(), next.path("userMessageId").asText()).isEmpty());
        call(runPath(next) + "/cancel", "POST", null);
    }

    @Test
    void closingRealServletConnectionCancelsBeforeFirstText() throws Exception {
        var run = create("BEFORE_disconnect", "COMMON");
        try (InputStream stream = open(run)) {
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertTrue(reader.readLine().startsWith("id:"));
            await(() -> model.count("BEFORE_disconnect") == 1);
        }
        await(() -> state(run).path("run").path("status").asText().equals("CANCELLED"));
        assertEquals("CLIENT_DISCONNECTED", state(run).path("reasonCode").asText());
        await(() -> model.interrupted.containsKey("BEFORE_disconnect"));
    }

    @Test
    void concurrentRunsAreBoundedAndUnsubscribedRunCanExpire() throws Exception {
        var a = create("BEFORE_capacity_a", "COMMON");
        var b = create("BEFORE_capacity_b", "COMMON");
        var c = create("pending_capacity", "COMMON");
        try (InputStream first = open(a); InputStream second = open(b)) {
            assertEquals(429, call(runPath(c) + "/events", "GET", null).statusCode());
            assertEquals("READY", state(c).path("run").path("status").asText());
            db.update("UPDATE chat_run SET created_at = CURRENT_TIMESTAMP - INTERVAL 2 MINUTE WHERE run_id = ?",
                    c.path("runId").asText());
            await(() -> state(c).path("run").path("status").asText().equals("CANCELLED"));
            assertEquals("NOT_STARTED", state(c).path("reasonCode").asText());
            assertEquals(0, model.count("pending_capacity"));
        } finally {
            call(runPath(a) + "/cancel", "POST", null);
            call(runPath(b) + "/cancel", "POST", null);
            await(() -> state(a).path("run").path("status").asText().equals("CANCELLED"));
            await(() -> state(b).path("run").path("status").asText().equals("CANCELLED"));
        }
    }

    @Test
    void rerunningMigrationKeepsLegacyHistoryAndAcceptsNullRunIds() {
        String legacy = System.getenv("CHAT_IT_LEGACY_JDBC");
        assertNotNull(legacy);
        assertTrue(legacy.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ke_chat_test_[a-z0-9]+legacy\\?.+"));
        var source = new DriverManagerDataSource(legacy, DB_USER, DB_PASSWORD);
        var old = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(source);
        old.execute("ALTER TABLE chat_message DROP INDEX uk_chat_message_run_type, DROP COLUMN run_id");
        old.execute("DROP TABLE chat_run");
        old.update("INSERT INTO chat_message(message_id, conversation_id, type, content) VALUES ('u', 'old', 'USER', '老问题'),"
                + " ('a', 'old', 'ASSISTANT', '老回答')");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("sql/upgrade-chat-runs.sql"));
        migration.execute(source);
        migration.execute(source);
        assertEquals(List.of("老问题", "老回答"), old.queryForList(
                "SELECT content FROM chat_message WHERE run_id IS NULL ORDER BY id", String.class));
    }

    private static JsonNode create(String question, String mode) throws Exception {
        return create(question, mode, null);
    }

    private static JsonNode create(String question, String mode, String conversation) throws Exception {
        var command = JSON.createObjectNode().put("clientRequestId", UUID.randomUUID().toString())
                .put("mode", mode).put("content", question);
        if (conversation != null) command.put("conversationId", conversation);
        var response = call("/chat/runs", "POST", command.toString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static JsonNode state(JsonNode run) throws Exception {
        var response = call(runPath(run), "GET", null);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static InputStream open(JsonNode run) throws Exception {
        var response = HTTP.send(request(runPath(run) + "/events", "GET", null), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static String runPath(JsonNode run) { return "/chat/runs/" + run.path("runId").asText(); }

    private static HttpResponse<String> call(String path, String method, String body) throws Exception {
        return HTTP.send(request(path, method, body), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(String path, String method, String body) {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(12));
        if (body != null) request.header("Content-Type", "application/json");
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    @FunctionalInterface
    interface Condition { boolean met() throws Exception; }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(9).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.met()) return;
            Thread.sleep(100);
        }
        fail("Condition was not met");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EnableScheduling
    @MapperScan("know.engine.chat.mapper")
    @Import({ChatSettings.class, ChatService.class, ChatRunService.class, ChatRunStore.class, OnlineChatWork.class,
            ChatModelGateway.class, RagRetrievalService.class, PromptService.class, DatabaseChatMemoryStore.class,
            ChatConversationServiceImpl.class, ChatMessageServiceImpl.class, ChatController.class,
            ChatConversationController.class, ChatMessageController.class, ChatMvcConfig.class, MybatisPlusConfig.class,
            MyMetaObjectHandler.class})
    static class TestApplication {
        @Bean({"onlineChatModel", "titleChatModel"})
        ChatModel intent() {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    return ChatResponse.builder().aiMessage(
                            AiMessage.from("{\"related\":false,\"intent\":\"chat\",\"reasoning\":\"fixture\"}")).build();
                }
            };
        }

        @Bean({"ragChatModel", "onlineCommonChatModel"})
        FakeModel model() { return new FakeModel(); }

        @Bean TitleSummaryService title() { return question -> question; }
        @Bean("queryEmbeddingModel") OpenAiEmbeddingModel embedding() { return mock(OpenAiEmbeddingModel.class); }
        @Bean DocumentService documents() { return mock(DocumentService.class); }
        @Bean SegmentService segments() { return mock(SegmentService.class); }
        @Bean ElasticsearchRagRetriever search() { return mock(ElasticsearchRagRetriever.class); }
        @Bean DashScopeScoringModel rerank() { return mock(DashScopeScoringModel.class); }
    }

    static final class FakeModel implements StreamingChatModel {
        private final Map<String, AtomicInteger> started = new ConcurrentHashMap<>();
        private final Map<String, Boolean> interrupted = new ConcurrentHashMap<>();

        int count(String question) { return started.getOrDefault(question, new AtomicInteger()).get(); }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            String question = ((dev.langchain4j.data.message.UserMessage) request.messages().getLast()).singleText();
            started.computeIfAbsent(question, ignored -> new AtomicInteger()).incrementAndGet();
            if (question.startsWith("HOLD_") || question.startsWith("BEFORE_")) {
                if (question.startsWith("HOLD_")) handler.onPartialResponse("已收到部分回答");
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException error) {
                    interrupted.put(question, true);
                }
                return;
            }
            if (question.startsWith("FAIL_")) {
                handler.onPartialResponse("已收到部分回答");
                handler.onError(new IllegalStateException("fixture disconnect"));
                return;
            }
            if (question.startsWith("BURST_")) {
                for (int i = 0; i < 1000; i++) handler.onPartialResponse("快");
                return;
            }
            String answer = "[WARN]:普通文本\n第二行";
            handler.onPartialResponse(answer);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(answer))
                    .finishReason(FinishReason.STOP).build());
        }
    }
}
