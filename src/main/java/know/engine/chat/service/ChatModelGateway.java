package know.engine.chat.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import know.engine.ai.config.OnlineModelHttpClient.IncompleteModelStream;
import know.engine.rag.PromptBudget;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static know.engine.chat.service.ChatFailure.Reason.*;

/**
 * 将两个生成 Bean 适配成同一个业务流。每个片段是 Delta，模型明确结束后才发 Completed。
 * 线上 OpenAI 兼容客户端在读取响应头前登记 HTTP 取消句柄，仍由有界工作线程执行阻塞读取。
 */
@Component
class ChatModelGateway {
    sealed interface ModelEvent permits Delta, Completed {}
    record Delta(String text) implements ModelEvent {}
    record Completed(ChatResponse response) implements ModelEvent {}

    private final StreamingChatModel rag;
    private final StreamingChatModel common;
    private final OnlineChatWork work;
    private final ChatSettings settings;

    ChatModelGateway(@Qualifier("ragChatModel") StreamingChatModel rag,
                     @Qualifier("onlineCommonChatModel") StreamingChatModel common, OnlineChatWork work,
                     ChatSettings settings) {
        this.rag = rag;
        this.common = common;
        this.work = work;
        this.settings = settings;
    }

    Flux<ModelEvent> generate(boolean useRag, List<ChatMessage> messages, ChatBudget budget) {
        return Flux.defer(() -> {
            budget.requireTotal();
            var model = useRag ? rag : common;
            var first = budget.firstAnswer();
            if (first.isZero()) return Flux.error(new ChatFailure(FIRST_ANSWER_TIMEOUT, "等待首字超时"));
            return work.<ModelEvent>stream(budget.total(), first, settings.getStreamIdleTimeout(), sink -> {
                var terminal = new CompletableFuture<Void>();
                Runnable abort = OnlineChatWork.currentTransportAbort();
                model.chat(messages, new StreamingChatResponseHandler() {
                    private int answerBytes;
                    private boolean ended;

                    @Override
                    public synchronized void onPartialResponse(String text) {
                        if (sink.isCancelled() || ended || text == null || text.isEmpty()) return;
                        // Keep each event small and split only at a code point boundary.
                        for (int from = 0; from < text.length() && !sink.isCancelled();) {
                            int to = Math.min(text.length(), from + 512);
                            if (to < text.length() && Character.isHighSurrogate(text.charAt(to - 1))) to--;
                            String piece = text.substring(from, to);
                            int bytes = PromptBudget.bytes(piece);
                            if (answerBytes + bytes > settings.getMaxAnswerBytes()) {
                                onError(new ChatFailure(OUTPUT_LIMIT, "回答达到输出限制"));
                                return;
                            }
                            answerBytes += bytes;
                            sink.next(new Delta(piece));
                            from = to;
                        }
                    }

                    @Override
                    public synchronized void onCompleteResponse(ChatResponse response) {
                        if (sink.isCancelled() || ended) return;
                        if (response == null) {
                            onError(new ChatFailure(UPSTREAM_PROTOCOL, "模型完成响应缺失"));
                            return;
                        }
                        ended = true;
                        try {
                            sink.next(new Completed(response));
                            sink.complete();
                        } finally {
                            // 先发完成事件，再唤醒等待线程，避免工作线程先关闭 Flux。
                            terminal.complete(null);
                        }
                    }

                    @Override
                    public synchronized void onError(Throwable error) {
                        Exceptions.throwIfFatal(error);
                        if (!sink.isCancelled() && !ended) {
                            ended = true;
                            try {
                                sink.error(classify(error));
                            } finally {
                                abort.run();
                                terminal.complete(null);
                            }
                        }
                    }
                });
                // The starter can return before callbacks. The worker remains reserved until a terminal
                // callback arrives, the starter exits, or the owning subscription cancels the wait.
                if (!sink.isCancelled()) terminal.get();
            }).transformDeferred(events -> {
                        var sawText = new java.util.concurrent.atomic.AtomicBoolean();
                        return events.doOnNext(event -> {
                            if (event instanceof Delta) sawText.set(true);
                        }).onErrorMap(java.util.concurrent.TimeoutException.class, error -> sawText.get()
                                ? new ChatFailure(STREAM_IDLE_TIMEOUT, "模型输出空闲超时")
                                : new ChatFailure(FIRST_ANSWER_TIMEOUT, "等待首字超时"));
                    })
                    .onErrorMap(error -> !(error instanceof OnlineChatWork.Unavailable), ChatModelGateway::classify);
        });
    }

    private static ChatFailure classify(Throwable error) {
        if (error instanceof ChatFailure failure) return failure;
        if (Exceptions.isOverflow(error)) return new ChatFailure(SLOW_CONSUMER, "接收速度过慢");
        // SDK 会把传输层的 IOException 包装成 LangChain4jException。
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IncompleteModelStream || cause instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                return new ChatFailure(UPSTREAM_PROTOCOL, "模型流未正常结束");
            }
            if (cause.getCause() == cause) break;
        }
        return new ChatFailure(UPSTREAM_INTERRUPTED, "模型生成中断");
    }
}
