package know.engine.chat.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import know.engine.ai.constant.PromptResources;
import know.engine.ai.prompt.PromptService;
import know.engine.ai.service.IntentRecognitionService;
import know.engine.rag.PromptBudget;
import know.engine.rag.RagContext.Prepared;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Function;

import static know.engine.chat.service.ChatFailure.Reason.*;
import static know.engine.chat.service.OnlineChatWork.Stage.INTENT;

/** 线上和评测共用的单轮编排。这里产出业务事件，HTTP 编码和会话落库由外围处理。 */
@Service
public class ChatService {
    public enum Mode { AUTO, RAG, COMMON }

    /** 原始问题进入生成模型。改写后的查询仅供检索和重排，另存于上下文供回看。 */
    public record Request(String question, Mode mode) {}

    sealed interface ExecutionEvent permits Progress, Warning, References, Delta, Done {}
    public record Progress(String stage, String message) implements ExecutionEvent {}
    public record Warning(String code, String stage, String message) implements ExecutionEvent {}
    record References(Prepared context) implements ExecutionEvent {}
    public record Delta(String text) implements ExecutionEvent {}
    record Done(ChatFailure.Reason reason, String generationSource, String modelName, String finishReason,
                Integer tokenCount) implements ExecutionEvent {}

    public record GenerationResult(String answer, Prepared context, List<String> degradedStages,
                                   String status, String reasonCode, String modelName, long elapsedMillis) {}

    private static final String NO_EVIDENCE_ANSWER = "当前资料不足，无法根据知识库回答这个问题。";
    private final ChatModel intentModel;
    private final ChatModelGateway models;
    private final RagRetrievalService retrieval;
    private final PromptService prompts;
    private final OnlineChatWork work;
    private final ChatSettings settings;
    private final Semaphore evaluation = new Semaphore(2);

    ChatService(@Qualifier("onlineChatModel") ChatModel intentModel, ChatModelGateway models,
                RagRetrievalService retrieval, PromptService prompts, OnlineChatWork work, ChatSettings settings) {
        this.intentModel = intentModel;
        this.models = models;
        this.retrieval = retrieval;
        this.prompts = prompts;
        this.work = work;
        this.settings = settings;
    }

    void validateQuestion(String question) {
        if (question == null || question.length() > settings.getMaxQuestionBytes() || question.isBlank()
                || PromptBudget.bytes(question) > settings.getMaxQuestionBytes()) {
            throw new IllegalArgumentException("请输入非空问题，且 UTF-8 编码不超过 " + settings.getMaxQuestionBytes() + " 字节");
        }
        for (boolean rag : List.of(true, false)) {
            PromptBudget.fit(settings.inputByteLimit(), system(rag), rag ? retrieval.inject(question, "") : question, List.of());
        }
    }

    /**
     * 订阅后依次准备历史、路由、检索资料、保存上下文和生成。
     * 线上将阶段进度直接发到有界 SSE 出口。评测复用同一条链，只收集业务结果。
     */
    Flux<ExecutionEvent> execute(Request request, Mono<List<ChatMessage>> history, ChatBudget budget,
                                 Consumer<Progress> onProgress, Consumer<Warning> onWarning,
                                 Function<Prepared, Mono<Void>> savePrepared) {
        return Flux.defer(() -> {
            Map<String, Warning> warnings = new java.util.concurrent.ConcurrentSkipListMap<>();
            Consumer<Warning> warn = warning -> {
                if (warnings.putIfAbsent(warning.code(), warning) == null) onWarning.accept(warning);
            };
            Mono<Input> preparation = history.flatMap(snapshot -> route(request, snapshot, budget, onProgress, warn)
                    .flatMap(rag -> {
                        String system = system(rag);
                        var fit = PromptBudget.fit(settings.inputByteLimit(), system,
                                rag ? retrieval.inject(request.question(), "") : request.question(), snapshot);
                        Mono<Prepared> context = rag
                                ? retrieval.prepare(request.question(), fit.history(), fit, budget, onProgress, warn)
                                : Mono.just(new Prepared(request.question(), List.of(), List.of(),
                                    settings.inputByteLimit(), fit.usedBytes(), true));
                        return context.map(prepared -> new Input(rag, system, fit.history(), prepared));
                    })).flatMap(input -> savePrepared.apply(input.context()).thenReturn(input));
            return Flux.concat(Mono.just(new Progress("PREPARATION", "正在准备回答...")),
                    preparation.timeout(Mono.delay(budget.preparation()),
                            Mono.error(new ChatFailure(PREPARATION_TIMEOUT, "准备回答超时")))
                        .onErrorMap(OnlineChatWork.StageTimeout.class, error -> error.phaseLimited
                                ? new ChatFailure(PREPARATION_TIMEOUT, "准备回答超时") : error)
                        .flatMapMany(input -> Flux.concat(Flux.fromIterable(warnings.values()),
                                Mono.just(new References(input.context())), answer(request.question(), input, budget))));
        }).transformDeferred(source -> OnlineChatWork.untilDeadline(source, budget.total(),
                () -> new ChatFailure(TOTAL_TIMEOUT, "本轮回答超时")));
    }

    private Mono<Boolean> route(Request request, List<ChatMessage> history, ChatBudget budget,
                                Consumer<Progress> progress, Consumer<Warning> warnings) {
        if (request.mode() != Mode.AUTO) return Mono.just(request.mode() == Mode.RAG);
        progress.accept(new Progress("INTENT", "正在识别您的意图..."));
        // 意图模型只读这一轮的历史快照，临时记忆随分类结束丢弃。
        return work.run(INTENT, budget::preparation, () -> {
            var chatMemory = MessageWindowChatMemory.withMaxMessages(10);
            PromptBudget.fit(settings.inputByteLimit(), system(true), request.question(), history)
                    .history().forEach(chatMemory::add);
            var intent = AiServices.builder(IntentRecognitionService.class)
                    .chatModel(intentModel).chatMemory(chatMemory).build();
            // 单一 ChatMemory 使用 LangChain4j 的默认记忆 ID，自定义 MemoryId 需要另配 provider。
            return intent.chat(request.question()).related();
        }).onErrorResume(error -> {
            budget.allowPreparationFallback(error);
            work.degraded(INTENT);
            warnings.accept(new Warning("INTENT_UNAVAILABLE", "INTENT", "意图识别暂不可用，使用知识检索"));
            return Mono.just(true);
        });
    }

    /**
     * 闲聊使用固定闲聊提示词和同一份历史。知识问答将选中的父块或子块文本注入问题，
     * 和独立的系统提示词一起交给生成模型。无证据时直接返回本地结果。
     */
    private Flux<ExecutionEvent> answer(String question, Input input, ChatBudget budget) {
        budget.requireTotal();
        if (input.rag() && input.context().items().isEmpty()) {
            return Flux.just(new Delta(NO_EVIDENCE_ANSWER), new Done(NO_EVIDENCE, "LOCAL", null, null, null));
        }
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from(input.system()));
        messages.addAll(input.history());
        messages.add(UserMessage.from(input.rag() ? retrieval.inject(question, input.context().contents()) : question));
        return Flux.concat(Mono.just(new Progress("GENERATION", "正在生成回答...")),
                Flux.defer(() -> {
                    var sawText = new java.util.concurrent.atomic.AtomicBoolean();
                    return models.generate(input.rag(), messages, budget).map(event -> {
                        if (event instanceof ChatModelGateway.Delta delta) {
                            if (!delta.text().isBlank()) sawText.set(true);
                            return new Delta(delta.text());
                        }
                        var response = ((ChatModelGateway.Completed) event).response();
                        var finish = response.finishReason();
                        ChatFailure.Reason reason = finish == FinishReason.LENGTH ? OUTPUT_LIMIT
                                : finish == FinishReason.CONTENT_FILTER ? CONTENT_FILTER
                                : finish != null && finish != FinishReason.STOP ? UPSTREAM_PROTOCOL
                                : sawText.get() ? SUCCESS : EMPTY_ANSWER;
                        return new Done(reason, "PROVIDER", response.modelName(), finish == null ? null : finish.name(),
                                response.tokenUsage() == null ? null : response.tokenUsage().totalTokenCount());
                    });
                }));
    }

    public Mono<GenerationResult> evaluate(Request request, List<ChatMessage> history) {
        return Mono.defer(() -> {
            validateQuestion(request.question());
            if (!evaluation.tryAcquire()) return Mono.error(new OnlineChatWork.Busy());
            var budget = new ChatBudget(settings, System::nanoTime);
            var answer = new StringBuilder();
            var warnings = new java.util.concurrent.ConcurrentSkipListMap<String, Warning>();
            var context = new java.util.concurrent.atomic.AtomicReference<Prepared>();
            return execute(request, Mono.just(List.copyOf(history)), budget, ignored -> {},
                    warning -> warnings.put(warning.code(), warning),
                    ignored -> Mono.empty()).handle((event, sink) -> {
                switch (event) {
                    case References refs -> context.set(refs.context());
                    case Delta text -> answer.append(text.text());
                    case Done done -> sink.next(new GenerationResult(answer.toString(), context.get(),
                            List.copyOf(warnings.keySet()), success(done.reason()) ? "COMPLETED" : "FAILED",
                            done.reason().name(), done.modelName(), budget.elapsedMillis()));
                    default -> { }
                }
            }).cast(GenerationResult.class).last()
                .onErrorResume(error -> Mono.just(new GenerationResult(answer.toString(), context.get(),
                        List.copyOf(warnings.keySet()), "FAILED", reason(error).name(), null, budget.elapsedMillis())))
                .doFinally(ignored -> evaluation.release());
        });
    }

    static boolean success(ChatFailure.Reason reason) {
        return reason == SUCCESS || reason == NO_EVIDENCE;
    }

    static ChatFailure.Reason reason(Throwable error) {
        if (error instanceof ChatFailure failure) return failure.reason;
        if (error instanceof OnlineChatWork.Busy) return CAPACITY_EXCEEDED;
        if (error instanceof OnlineChatWork.StageTimeout timeout && timeout.phaseLimited) return TOTAL_TIMEOUT;
        return INTERNAL_ERROR;
    }

    private String system(boolean rag) {
        return prompts.getPrompt(rag ? PromptResources.GENERIC_QA : PromptResources.COMMON_CHAT_SYSTEM);
    }

    private record Input(boolean rag, String system, List<ChatMessage> history, Prepared context) {}
}
