package know.engine.chat.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import know.engine.ai.constant.PromptResources;
import know.engine.ai.prompt.PromptService;
import know.engine.document.service.DocumentService;
import know.engine.document.service.SegmentService;
import know.engine.rag.ElasticsearchRagRetriever;
import know.engine.rag.KnowEngineQueryTransformer;
import know.engine.rag.PromptBudget;
import know.engine.rag.RagContext;
import know.engine.rag.RagContext.Branch;
import know.engine.rag.RagContext.Candidate;
import know.engine.rag.RagContext.Hit;
import know.engine.rag.RagContext.Prepared;
import know.engine.rag.reranker.DashScopeScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static know.engine.chat.service.ChatFailure.Reason.*;
import static know.engine.chat.service.OnlineChatWork.Stage.*;
import static know.engine.rag.RagContext.Availability.SUCCESS;

/** 显式编排改写、双路召回、融合、重排和上下文装配。每个阻塞依赖走对应的有界执行池。 */
@Service
@Slf4j
class RagRetrievalService {
    private final ChatModel chat;
    private final OpenAiEmbeddingModel embedding;
    private final DocumentService documents;
    private final SegmentService segments;
    private final ElasticsearchRagRetriever search;
    private final DashScopeScoringModel reranker;
    private final PromptService prompts;
    private final OnlineChatWork work;
    private final ChatSettings settings;

    RagRetrievalService(@Qualifier("onlineChatModel") ChatModel chat,
                        @Qualifier("queryEmbeddingModel") OpenAiEmbeddingModel embedding,
                        DocumentService documents, SegmentService segments, ElasticsearchRagRetriever search,
                        DashScopeScoringModel reranker, PromptService prompts, OnlineChatWork work, ChatSettings settings) {
        this.chat = chat;
        this.embedding = embedding;
        this.documents = documents;
        this.segments = segments;
        this.search = search;
        this.reranker = reranker;
        this.prompts = prompts;
        this.work = work;
        this.settings = settings;
        if (settings.getRerankThreshold() != null && !reranker.modelName().equals(settings.getRerankThresholdModel())) {
            throw new IllegalArgumentException("The rerank threshold must be calibrated for the configured model");
        }
    }

    Mono<Prepared> prepare(String question, List<ChatMessage> history, PromptBudget.Fit fit, ChatBudget budget,
                           Consumer<ChatService.Progress> progress, Consumer<ChatService.Warning> warnings) {
        if (!history.isEmpty()) progress.accept(new ChatService.Progress("REWRITE", "正在优化您的问题..."));
        // 当前轮只读取一次生效版本列表。任何一次读取失败都结束检索，防止扩大到整个索引。
        return rewrite(question, history, budget, warnings).flatMap(query -> {
            progress.accept(new ChatService.Progress("RETRIEVAL", "正在检索知识库内容..."));
            return work.run(DB, budget::preparation, () -> List.copyOf(documents.activeVersionIds()))
                        .onErrorMap(error -> {
                            budget.allowPreparationFallback(error);
                            return new ChatFailure(RETRIEVAL_UNAVAILABLE, "无法读取生效的文档版本");
                        })
                        .flatMap(scope -> retrieve(query, scope, budget, warnings))
                        .flatMap(candidates -> {
                            progress.accept(new ChatService.Progress("RANKING", "正在排序筛选结果..."));
                            return rank(query, candidates, budget, warnings);
                        })
                        .flatMap(ordered -> contexts(query, ordered, fit, budget, warnings));
        });
    }

    private Mono<String> rewrite(String question, List<ChatMessage> history, ChatBudget budget,
                                 Consumer<ChatService.Warning> warnings) {
        if (history.isEmpty()) return Mono.just(question); // 单轮问题直接检索，保留原始编号、术语和状态。
        return work.run(REWRITE, budget::preparation, () -> new KnowEngineQueryTransformer(
                        chat, prompts.getPromptTemplate(PromptResources.QUERY_REWRITE)).rewrite(question, history))
                .filter(query -> !query.isBlank())
                .switchIfEmpty(Mono.error(new IllegalStateException("Empty rewrite")))
                .onErrorResume(error -> {
                    budget.allowPreparationFallback(error);
                    warn(warnings, REWRITE, "REWRITE_UNAVAILABLE", "问题改写暂不可用，使用本轮和上一轮问题");
                    for (int i = history.size() - 1; i >= 0; i--) {
                        if (history.get(i) instanceof UserMessage previous) {
                            return Mono.just(previous.singleText() + "\n" + question);
                        }
                    }
                    return Mono.just(question);
                });
    }

    private Mono<List<Candidate>> retrieve(String query, List<Long> scope, ChatBudget budget,
                                            Consumer<ChatService.Warning> warnings) {
        if (scope.isEmpty()) return Mono.just(List.of());
        Mono<Branch> keyword = branch(work.run(SEARCH, budget::preparation, () -> search.retrieve(query, scope, null)),
                budget, warnings, "KEYWORD_UNAVAILABLE");
        Mono<Branch> vector = settings.isVectorEnabled()
                ? work.run(EMBEDDING, budget::preparation, () -> embedding.embed(query).content().vectorAsList())
                    .map(java.util.Optional::of)
                    .onErrorResume(error -> {
                        budget.allowPreparationFallback(error);
                        warn(warnings, EMBEDDING, "EMBEDDING_UNAVAILABLE", "向量检索暂不可用");
                        return Mono.just(java.util.Optional.empty());
                    }).flatMap(values -> values.isPresent()
                            ? branch(work.run(SEARCH, budget::preparation, () -> search.retrieve(query, scope, values.get())),
                                budget, warnings, "VECTOR_UNAVAILABLE")
                            : Mono.just(Branch.unavailable()))
                : Mono.just(Branch.disabled());
        // 各路都发出 Branch，包括关闭、降级和空命中，保证 zip 总能区分无证据与依赖故障。
        return Mono.zip(keyword, vector, (text, semantic) -> {
            if (text.availability() != SUCCESS && semantic.availability() != SUCCESS) {
                throw new ChatFailure(RETRIEVAL_UNAVAILABLE, "知识检索暂不可用，请稍后再试");
            }
            List<Candidate> fused = RagContext.fuse(text, semantic);
            log.debug("RAG keyword={} vector={} fused={}", text.hits().size(), semantic.hits().size(), fused.size());
            return fused;
        });
    }

    private Mono<Branch> branch(Mono<List<Hit>> call, ChatBudget budget,
                                Consumer<ChatService.Warning> warnings, String code) {
        return call.map(Branch::successful).onErrorResume(error -> {
            budget.allowPreparationFallback(error);
            warn(warnings, SEARCH, code, "一路知识检索暂不可用");
            return Mono.just(Branch.unavailable());
        });
    }

    /**
     * RRF 解决两路原始分数量纲不同的问题，提供基础排序。重排模型用同一个检索问题重新评估
     * 子块相关性，正常时按重排分为主排序。重排关闭或可恢复的调用故障时继续用 RRF。
     */
    private Mono<List<Candidate>> rank(String query, List<Candidate> candidates, ChatBudget budget,
                                       Consumer<ChatService.Warning> warnings) {
        if (candidates.isEmpty() || !settings.isRerankEnabled()
                || candidates.size() == 1 && settings.getRerankThreshold() == null) return Mono.just(candidates);
        var selected = candidates.subList(0, Math.min(settings.getRerankMaxCandidates(), candidates.size()));
        // 整批子块只请求一次打分。百炼响应按候选索引还原分数，保留原来的片段身份和双路来源。
        return work.run(RERANK, budget::preparation, () -> {
            var scores = reranker.scoreAll(selected.stream().map(c -> TextSegment.from(c.hit().text())).toList(), query).content();
            var scored = new ArrayList<Candidate>();
            Double threshold = settings.getRerankThreshold();
            for (int i = 0; i < selected.size(); i++) {
                var candidate = selected.get(i);
                double score = scores.get(i);
                if (threshold == null || score >= threshold) scored.add(new Candidate(candidate.keyword(), candidate.vector(), score));
            }
            // 分数相同时继续按 RRF 排序。阈值过滤为空时保留空结果，由上层给出资料不足的回答。
            scored.sort(Comparator.comparingDouble(Candidate::rerankScore).reversed().thenComparing(RagContext.RRF_ORDER));
            log.debug("RAG reranked={} accepted={}", selected.size(), scored.size());
            return List.copyOf(scored);
        }).onErrorResume(error -> {
            budget.allowPreparationFallback(error);
            String code = error instanceof DashScopeScoringModel.ProtocolException ? "RERANK_PROTOCOL" : "RERANK_UNAVAILABLE";
            warn(warnings, RERANK, code, "重排暂不可用，使用融合排序");
            return Mono.just(candidates);
        });
    }

    private Mono<Prepared> contexts(String query, List<Candidate> ordered, PromptBudget.Fit fit, ChatBudget budget,
                                     Consumer<ChatService.Warning> warnings) {
        if (ordered.isEmpty()) return Mono.just(new Prepared(query, List.of(), List.of(),
                settings.inputByteLimit(), fit.usedBytes(), true));
        return work.run(DB, budget::preparation, () -> RagContext.select(ordered, key -> {
            budget.preparation();
            if (Thread.currentThread().isInterrupted()) throw new ChatFailure(CLIENT_DISCONNECTED, "检索已停止");
            try {
                String parent = segments.getParentText(key.docId(), key.versionId(), key.chunkId());
                if (parent == null) warn(warnings, DB, "PARENT_UNAVAILABLE", "部分父块不可用，使用命中分块");
                return new RagContext.ParentText(parent, parent == null);
            } catch (RuntimeException error) {
                budget.allowPreparationFallback(error);
                warn(warnings, DB, "PARENT_UNAVAILABLE", "部分父块不可用，使用命中分块");
                return new RagContext.ParentText(null, true);
            }
        }, fit.availableBytes())).onErrorResume(error -> {
            budget.allowPreparationFallback(error);
            warn(warnings, DB, "PARENT_UNAVAILABLE", "父块读取暂不可用，使用命中分块");
            return Mono.fromSupplier(() -> RagContext.select(ordered, ignored -> new RagContext.ParentText(null, true),
                    fit.availableBytes()));
        }).map(selection -> {
            var context = new Prepared(query, selection.items(), selection.references(), settings.inputByteLimit(),
                    fit.usedBytes() + PromptBudget.bytes(String.join("\n\n",
                            selection.items().stream().map(RagContext.ContextItem::text).toList())), true);
            log.debug("RAG contexts={} inputBytes={} parentFallbacks={}", context.items().size(),
                    context.usedInputBytes(), selection.parentFallbacks());
            return context;
        });
    }

    private void warn(Consumer<ChatService.Warning> warnings, OnlineChatWork.Stage stage, String code, String message) {
        work.degraded(stage);
        warnings.accept(new ChatService.Warning(code, stage.name(), message));
    }

    String inject(String question, String contents) {
        return prompts.getPromptTemplate(PromptResources.RAG_CONTENT_INJECTOR)
                .apply(Map.of("userMessage", question, "contents", contents)).text();
    }
}
