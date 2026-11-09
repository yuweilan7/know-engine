package know.engine.chat.service;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import know.engine.ai.config.OnlineModelHttpClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChatModelGatewayTest {
    @Test
    void asyncStarterReturnAndDuplicateCallbacksDoNotEndOrRestartTheStream() {
        var settings = new ChatSettings();
        var work = new OnlineChatWork(mock(javax.sql.DataSource.class), settings);
        var handler = new CompletableFuture<StreamingChatResponseHandler>();
        StreamingChatModel model = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler callback) {
                handler.complete(callback);
            }
        };
        try {
            StepVerifier.create(stream(model, work, settings))
                    .then(() -> handler.join().onPartialResponse("first"))
                    .expectNext(new ChatModelGateway.Delta("first"))
                    .then(() -> {
                        var response = ChatResponse.builder().aiMessage(AiMessage.from("first")).build();
                        handler.join().onCompleteResponse(response);
                        handler.join().onCompleteResponse(response);
                        handler.join().onPartialResponse("late");
                        handler.join().onError(new IllegalStateException("late"));
                    })
                    .expectNextCount(1)
                    .expectComplete().verify(Duration.ofSeconds(5));
        } finally {
            work.shutdown();
        }
    }

    @Test
    void explicitWireCompletionIsRequiredEvenAfterTextAndStopFinishReason() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var settings = new ChatSettings();
        var work = new OnlineChatWork(mock(javax.sql.DataSource.class), settings);
        try (var ignored = new AutoCloseable() { public void close() { upstream.stop(0); work.shutdown(); } }) {
            upstream.createContext("/complete/v1/chat/completions", exchange -> reply(exchange, true));
            upstream.createContext("/truncated/v1/chat/completions", exchange -> reply(exchange, false));
            upstream.start();
            var transport = new OnlineModelHttpClient();
            try {
                for (String route : List.of("complete", "truncated")) {
                    var model = OpenAiStreamingChatModel.builder().apiKey("fixture").modelName("fixture")
                            .baseUrl("http://127.0.0.1:" + upstream.getAddress().getPort() + "/" + route + "/v1")
                            .httpClientBuilder(transport.builder()).build();
                    var verifier = StepVerifier.create(stream(model, work, settings))
                            .expectNext(new ChatModelGateway.Delta("部分文本"));
                    if (route.equals("complete")) {
                        verifier.expectNextMatches(event -> event instanceof ChatModelGateway.Completed)
                                .expectComplete().verify(Duration.ofSeconds(5));
                    } else {
                        verifier.expectErrorMatches(error -> error instanceof ChatFailure failure
                                && failure.reason == ChatFailure.Reason.UPSTREAM_PROTOCOL).verify(Duration.ofSeconds(5));
                    }
                }
            } finally {
                transport.destroy();
            }
        }
    }

    @Test
    void preparationAndFirstAnswerUseDifferentBudgetsAndTotalDoesNotRenew() {
        var settings = new ChatSettings();
        var clock = new AtomicLong();
        var budget = new ChatBudget(settings, clock::get);
        clock.set(Duration.ofSeconds(6).toNanos());
        var prep = assertThrows(ChatFailure.class, budget::preparation);
        assertEquals(ChatFailure.Reason.PREPARATION_TIMEOUT, prep.reason);
        assertEquals(Duration.ofSeconds(6), budget.firstAnswer());
        clock.set(Duration.ofSeconds(45).toNanos());
        var total = assertThrows(ChatFailure.class, budget::requireTotal);
        assertEquals(ChatFailure.Reason.TOTAL_TIMEOUT, total.reason);
    }

    @Test
    void cancellationClosesAllRegisteredHandlesAndAbortsLateRegistrations() throws Exception {
        var work = new OnlineChatWork(mock(javax.sql.DataSource.class));
        var entered = new CountDownLatch(1);
        var unblock = new CompletableFuture<Void>();
        var cancelled = new CountDownLatch(3);
        var task = work.run(OnlineChatWork.Stage.SEARCH, Duration.ofSeconds(2), () -> {
            OnlineChatWork.onTransportCancel(cancelled::countDown);
            OnlineChatWork.onTransportCancel(cancelled::countDown);
            entered.countDown();
            unblock.join();
            OnlineChatWork.onTransportCancel(cancelled::countDown);
            return true;
        }).toFuture();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            task.cancel(true);
            unblock.complete(null);
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        } finally {
            unblock.complete(null);
            work.shutdown();
        }
    }

    private static Flux<ChatModelGateway.ModelEvent> stream(StreamingChatModel model, OnlineChatWork work,
                                                           ChatSettings settings) {
        return new ChatModelGateway(model, model, work, settings)
                .generate(false, List.of(UserMessage.from("test")), new ChatBudget(settings, System::nanoTime));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, boolean done) throws java.io.IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String wire = """
                    data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{"content":"部分文本"},"finish_reason":null}]}

                    data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    """;
            if (done) wire += "data: [DONE]\n\n";
            exchange.getResponseBody().write(wire.getBytes(StandardCharsets.UTF_8));
        }
    }
}
