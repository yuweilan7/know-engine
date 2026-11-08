package know.engine.chat.service;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.service.AiServices;
import know.engine.ai.constant.PromptResources;
import know.engine.ai.prompt.PromptService;
import know.engine.ai.service.IntentRecognitionService;
import know.engine.ai.service.TitleSummaryService;
import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.constant.RetrievalSource;
import know.engine.chat.entity.ChatParam;
import know.engine.chat.entity.ChatMessage.RagReference;
import know.engine.chat.memory.DatabaseChatMemoryStore;
import know.engine.document.service.DocumentService;
import know.engine.document.service.SegmentService;
import know.engine.rag.KnowEngineElasticsearchContentRetriever;
import know.engine.rag.KnowEngineQueryTransformer;
import know.engine.rag.KnowEngineReRankingContentAggregator;
import know.engine.rag.reranker.DashScopeScoringModel;
import know.engine.rag.util.ReferenceUtil;
import know.engine.rag.util.RetrievalSourceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

import static know.engine.chat.service.OnlineChatWork.Stage.*;
import static know.engine.rag.constant.MetadataKeyConstant.*;

@Service
@Slf4j
public class ChatService {
    private final ChatModel chatModel;
    private final StreamingChatModel ragChatModel;
    private final StreamingChatModel commonChatModel;
    private final TitleSummaryService titleSummaryService;
    private final ChatConversationService conversations;
    private final ChatMessageService messages;
    private final DatabaseChatMemoryStore memory;
    private final SegmentService segments;
    private final DocumentService documents;
    private final ElasticsearchAsyncClient searchClient;
    private final OpenAiEmbeddingModel embeddingModel;
    private final PromptService prompts;
    private final OnlineChatWork work;
    private final TransactionTemplate transaction;
    private final KnowEngineReRankingContentAggregator ranking;
    private final Semaphore admission;
    private final Semaphore evalAdmission = new Semaphore(2);
    private final Duration preparationTimeout;
    private final Duration firstAnswerTimeout;
    private final Duration streamIdleTimeout;
    private final Duration totalTimeout;
    private final boolean vectorEnabled;
    private final boolean rerankEnabled;

    public ChatService(@Qualifier("onlineChatModel") ChatModel chatModel,
                       @Qualifier("ragChatModel") StreamingChatModel ragChatModel,
                       @Qualifier("onlineCommonChatModel") StreamingChatModel commonChatModel,
                       TitleSummaryService titleSummaryService, ChatConversationService conversations,
                       ChatMessageService messages, DatabaseChatMemoryStore memory, SegmentService segments,
                       DocumentService documents, ElasticsearchAsyncClient searchClient,
                       @Qualifier("queryEmbeddingModel") OpenAiEmbeddingModel embeddingModel,
                       PromptService prompts, OnlineChatWork work, TransactionTemplate transaction,
                       DashScopeScoringModel reranker,
                       @Value("${know-engine.chat.max-concurrent}") int maxConcurrent,
                       @Value("${know-engine.chat.preparation-timeout}") Duration preparationTimeout,
                       @Value("${know-engine.chat.first-answer-timeout}") Duration firstAnswerTimeout,
                       @Value("${know-engine.chat.stream-idle-timeout}") Duration streamIdleTimeout,
                       @Value("${know-engine.chat.total-timeout}") Duration totalTimeout,
                       @Value("${know-engine.chat.vector-enabled}") boolean vectorEnabled,
                       @Value("${know-engine.chat.rerank-enabled}") boolean rerankEnabled) {
        if (maxConcurrent < 1 || preparationTimeout.isNegative() || preparationTimeout.isZero()
                || firstAnswerTimeout.compareTo(preparationTimeout) <= 0
                || totalTimeout.compareTo(firstAnswerTimeout) <= 0
                || streamIdleTimeout.isNegative() || streamIdleTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid online chat limits");
        }
        this.chatModel = chatModel;
        this.ragChatModel = ragChatModel;
        this.commonChatModel = commonChatModel;
        this.titleSummaryService = titleSummaryService;
        this.conversations = conversations;
        this.messages = messages;
        this.memory = memory;
        this.segments = segments;
        this.documents = documents;
        this.searchClient = searchClient;
        this.embeddingModel = embeddingModel;
        this.prompts = prompts;
        this.work = work;
        this.transaction = transaction;
        this.ranking = KnowEngineReRankingContentAggregator.builder()
                .scoringModel(reranker)
                .querySelector(candidates -> candidates.keySet().iterator().next())
                .maxResults(10)
                .build();
        this.admission = new Semaphore(maxConcurrent);
        this.preparationTimeout = preparationTimeout;
        this.firstAnswerTimeout = firstAnswerTimeout;
        this.streamIdleTimeout = streamIdleTimeout;
        this.totalTimeout = totalTimeout;
        this.vectorEnabled = vectorEnabled;
        this.rerankEnabled = rerankEnabled;
    }

    public Flux<String> chat(String userId, String content, String conversationId) {
        return withinRound(admission, budget -> Flux.concat(
                Mono.just("[PROGRESS]:正在识别您的意图..."),
                work.run(DB, budget.preparation(), () -> prepare(userId, content, conversationId))
                        .flatMapMany(round -> identify(round, budget).flatMapMany(related ->
                                (related ? rag(round, budget) : generate(round, AnswerContext.common(content), budget))
                                        .concatWithValues("[DONE]:" + round.conversationId())))))
                .onErrorResume(error -> Flux.just("[WARN]:" + userError(error)));
    }

    /** Offline evaluation uses the same retrieval path with its own admission limit. */
    public Flux<String> ragChat(ChatParam param) {
        return withinRound(evalAdmission, budget -> work.run(DB, budget.preparation(), () ->
                        new Round(param.conversationId(), param.messageId(), param.assistantMessageId(), param.content(),
                                memory.loadHistory(param.conversationId(), param.messageId())))
                .flatMapMany(round -> rag(round, budget)));
    }

    private Flux<String> withinRound(Semaphore limit, Function<Budget, Flux<String>> action) {
        return Flux.defer(() -> {
            if (!limit.tryAcquire()) {
                log.info("Online chat admission rejected");
                return Flux.error(new OnlineChatWork.Busy());
            }
            Budget budget = new Budget(System.nanoTime());
            var failure = new java.util.concurrent.atomic.AtomicReference<String>();
            return OnlineChatWork.untilDeadline(Flux.defer(() -> action.apply(budget)), totalTimeout,
                            () -> new OnlineChatWork.Unavailable("本轮回答超时，请稍后再试"))
                    .doOnError(error -> failure.set(error.getClass().getSimpleName()))
                    .doFinally(signal -> {
                        limit.release();
                        log.info("Chat round status={} failure={} firstAnswerMs={} elapsedMs={}",
                                signal, failure.get(), budget.firstAnswerMillis, budget.elapsedMillis());
                    });
        });
    }

    private Round prepare(String userId, String content, String conversationId) {
        boolean newConversation = conversationId == null || conversationId.isBlank();
        Round round = transaction.execute(status -> {
            String id = newConversation ? conversations.createConversation(userId, content.substring(0, Math.min(20, content.length())))
                    : conversationId;
            String userMessage = messages.saveMessage(id, ChatMessageType.USER, content);
            String assistantMessage = messages.saveMessage(id, ChatMessageType.ASSISTANT, null);
            var history = newConversation ? List.<ChatMessage>of() : memory.loadHistory(id, userMessage);
            if (Thread.currentThread().isInterrupted()) throw new OnlineChatWork.Unavailable("会话准备超时");
            return new Round(id, userMessage, assistantMessage, content, history);
        });
        if (newConversation) {
            work.run(TITLE, TITLE.timeout, () -> titleSummaryService.generateTitle(content))
                    .flatMap(title -> work.run(DB, DB.timeout, () -> conversations.updateTitle(round.conversationId(), title)))
                    .subscribe(ignored -> {}, error -> work.degraded(TITLE));
        }
        return round;
    }

    private Mono<Boolean> identify(Round round, Budget budget) {
        return work.run(INTENT, budget.preparation(), () -> {
            var chatMemory = MessageWindowChatMemory.withMaxMessages(10);
            round.history().forEach(chatMemory::add);
            var intent = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel).chatMemory(chatMemory).build();
            return intent.chat(round.conversationId(), round.content()).related();
        }).onErrorResume(error -> {
            work.degraded(INTENT);
            return Mono.just(true);
        });
    }

    private Flux<String> rag(Round round, Budget budget) {
        return Flux.concat(
                Mono.just("[PROGRESS]:正在优化您的问题..."),
                rewrite(round, budget).flatMapMany(query -> Flux.concat(
                        Mono.just("[PROGRESS]:正在检索知识库内容..."),
                        work.run(DB, budget.preparation(), () -> List.copyOf(documents.activeVersionIds()))
                                .flatMap(scope -> retrieve(query, scope, budget))
                                .flatMapMany(lists -> Flux.concat(
                                        Mono.just("[PROGRESS]:正在排序筛选结果..."),
                                        rank(query, lists, budget).flatMapMany(ranked ->
                                                generate(round, AnswerContext.rag(query, ranked), budget)))))));
    }

    private Mono<String> rewrite(Round round, Budget budget) {
        if (round.history().isEmpty()) return Mono.just(round.content());
        return work.run(REWRITE, budget.preparation(), () -> new KnowEngineQueryTransformer(
                        chatModel, prompts.getPromptTemplate(PromptResources.QUERY_REWRITE))
                        .rewrite(round.content(), round.history()))
                .filter(query -> !query.isBlank())
                .onErrorResume(error -> {
                    work.degraded(REWRITE);
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.fromSupplier(() -> previousQuestion(round)));
    }

    private String previousQuestion(Round round) {
        for (int i = round.history().size() - 1; i >= 0; i--) {
            if (round.history().get(i) instanceof UserMessage previous) {
                return previous.singleText() + "\n" + round.content();
            }
        }
        return round.content();
    }

    private Mono<List<List<Content>>> retrieve(String query, List<Long> scope, Budget budget) {
        if (scope.isEmpty()) return Mono.just(List.of());
        var retriever = new KnowEngineElasticsearchContentRetriever(searchClient, scope);
        var keyword = branch(Mono.defer(() -> work.run(SEARCH, budget.preparation(), () ->
                RetrievalSourceUtil.tagContents(retriever.retrieve(query, null), RetrievalSource.KEYWORD))));
        if (!vectorEnabled) return keyword.map(result -> successfulBranches(result, Branch.disabled()));

        var vector = Mono.defer(() -> work.run(EMBEDDING, budget.preparation(), () ->
                        embeddingModel.embed(query).content().vectorAsList()))
                .onErrorResume(error -> {
                    work.degraded(EMBEDDING);
                    return Mono.empty();
                })
                .flatMap(embedding -> branch(work.run(SEARCH, budget.preparation(), () ->
                        RetrievalSourceUtil.tagContents(retriever.retrieve(query, embedding), RetrievalSource.VECTOR))))
                .defaultIfEmpty(Branch.disabled());
        return Mono.zip(keyword, vector, this::successfulBranches);
    }

    private Mono<Branch> branch(Mono<List<Content>> retrieval) {
        return retrieval.map(result -> new Branch(result, true))
                .onErrorResume(error -> {
                    work.degraded(SEARCH);
                    return Mono.just(Branch.disabled());
                });
    }

    private List<List<Content>> successfulBranches(Branch keyword, Branch vector) {
        if (!keyword.available() && !vector.available()) {
            throw new OnlineChatWork.Unavailable("知识检索暂不可用，请稍后再试");
        }
        return List.of(vector.contents(), keyword.contents());
    }

    private Mono<List<Content>> rank(String queryText, List<List<Content>> lists, Budget budget) {
        Query query = Query.from(queryText);
        List<Content> fused = ranking.fuse(Map.of(query, lists));
        if (fused.isEmpty()) return Mono.just(List.of());
        Mono<List<Content>> ordered = rerankEnabled && fused.size() > 1
                ? work.run(RERANK, budget.preparation(), () -> ranking.reRankAndFilter(fused, query))
                    .onErrorResume(error -> {
                        work.degraded(RERANK);
                        return Mono.just(fused);
                    })
                : Mono.just(fused);
        return ordered.flatMap(children -> work.run(DB, budget.preparation(), () -> expandParents(children))
                .onErrorResume(error -> {
                    work.degraded(DB);
                    return Mono.just(children.stream().limit(5).toList());
                }));
    }

    private List<Content> expandParents(List<Content> ranked) {
        List<Content> result = new ArrayList<>();
        var seenParents = new HashSet<String>();
        for (Content hit : ranked) {
            if (Thread.currentThread().isInterrupted()) break;
            var metadata = hit.textSegment().metadata();
            String parentId = metadata.getString(PARENT_CHUNK_ID);
            if (parentId != null) {
                if (!seenParents.add(parentId)) continue;
                String parent = segments.getParentText(metadata.getLong(DOC_ID), metadata.getLong(VERSION_ID), parentId);
                if (parent != null && parent.length() <= 12000) {
                    hit = Content.from(TextSegment.from(parent, metadata), hit.metadata());
                }
            }
            result.add(hit);
            if (result.size() == 5) break;
        }
        return result;
    }

    private Flux<String> generate(Round round, AnswerContext context, Budget budget) {
        return Flux.defer(() -> {
            StreamingChatModel model = context.rag() ? ragChatModel : commonChatModel;
            List<ChatMessage> request = new ArrayList<>();
            request.add(SystemMessage.from(prompts.getPrompt(
                    context.rag() ? PromptResources.GENERIC_QA : PromptResources.COMMON_CHAT_SYSTEM)));
            request.addAll(round.history());
            UserMessage user = UserMessage.from(round.content());
            request.add(context.rag() ? new DefaultContentInjector(prompts.getPromptTemplate(PromptResources.RAG_CONTENT_INJECTOR))
                    .inject(context.contents(), user) : user);

            var references = ReferenceUtil.getRagReferences(context.contents());
            var events = new ArrayList<String>();
            if (!references.isEmpty()) {
                Map<String, RagReference> perDocument = new LinkedHashMap<>();
                references.forEach(ref -> perDocument.putIfAbsent(ref.getDocumentId(), ref));
                events.add("[REFERENCE]:" + JSON.toJSONString(new ArrayList<>(perDocument.values())));
            }
            events.add("[PROGRESS]:正在生成回答...");

            StringBuilder answer = new StringBuilder();
            Flux<String> tokens = work.<String>stream(budget.total(), budget.firstAnswer(), streamIdleTimeout, sink ->
                    model.chat(request, new StreamingChatResponseHandler() {
                        @Override public void onPartialResponse(String token) {
                            if (!token.isEmpty() && !sink.isCancelled()) sink.next(token);
                        }

                        @Override public void onCompleteResponse(ChatResponse response) { }

                        @Override public void onError(Throwable error) { sink.error(error); }
                    }))
                    .doOnNext(token -> {
                        if (answer.isEmpty()) budget.firstAnswerMillis = budget.elapsedMillis();
                        answer.append(token);
                    });
            return Flux.concat(Flux.fromIterable(events), tokens, Mono.defer(() -> {
                if (answer.isEmpty()) return Mono.error(new OnlineChatWork.Unavailable("回答生成暂不可用，请稍后再试"));
                return work.run(DB, budget.total(), () -> {
                    transaction.executeWithoutResult(status -> {
                        if (!context.query().equals(round.content())) {
                            messages.updateTransformContent(round.userMessageId(), context.query());
                        }
                        if (!references.isEmpty()) messages.updateRagReferences(round.assistantMessageId(), references);
                        messages.updateContent(round.assistantMessageId(), answer.toString(), model.defaultRequestParameters().modelName());
                        if (Thread.currentThread().isInterrupted()) throw new OnlineChatWork.Unavailable("回答保存超时");
                    });
                    return "";
                }).then(Mono.empty());
            }));
        });
    }

    private String userError(Throwable error) {
        if (error instanceof OnlineChatWork.Unavailable) return error.getMessage();
        if (error instanceof java.util.concurrent.TimeoutException) return "等待回答超时，请稍后再试";
        log.warn("Online chat failed ({})", error.getClass().getSimpleName());
        return "本轮问答暂不可用，请稍后再试";
    }

    private record Round(String conversationId, String userMessageId, String assistantMessageId,
                         String content, List<ChatMessage> history) {}

    private record Branch(List<Content> contents, boolean available) {
        static Branch disabled() { return new Branch(List.of(), false); }
    }

    private record AnswerContext(String query, List<Content> contents, boolean rag) {
        static AnswerContext common(String query) { return new AnswerContext(query, List.of(), false); }
        static AnswerContext rag(String query, List<Content> contents) { return new AnswerContext(query, contents, true); }
    }

    private final class Budget {
        private final long start;
        private volatile long firstAnswerMillis = -1;
        Budget(long start) { this.start = start; }
        Duration preparation() { return remaining(preparationTimeout); }
        Duration firstAnswer() { return remaining(firstAnswerTimeout); }
        Duration total() { return remaining(totalTimeout); }
        long elapsedMillis() { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }
        private Duration remaining(Duration timeout) {
            return Duration.ofNanos(Math.max(0, timeout.toNanos() - (System.nanoTime() - start)));
        }
    }
}
