package know.engine.rag;

import know.engine.rag.reranker.KnowEngineDefaultContent;
import know.engine.rag.reranker.KnowEngineReciprocalRankFuser;
import know.engine.rag.util.RetrievalSourceUtil;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.DefaultContent;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static java.util.Collections.emptyList;

/**
 * RAG 召回结果的融合 + 重排聚合器
 *   RRF（Reciprocal Rank Fusion，倒数排名融合）：向量相似度与 BM25 分数量纲不同，不能直接比
 *       RRF 只看「排第几」，公式约为 {@code score += 1 / (k + rank)}（k 通常取 60），
 *       把多路有序列表合成一份候选集；同一片段在多路都靠前时，融合分会更高
 *   相关性重排：RRF 只融合排名，不判断「这段文字和问题是否真相关」
 *       {@link ScoringModel}对「Query + 片段」重新打分，
 *       再按 {@code minScore} 过滤、按 {@code maxResults} 截断，得到最终注入提示词的 Top-K
 *   空结果保护：若 minScore 过滤后为空，则按 RRF 顺序回退返回前 {@link #EMPTY_FILTER_FALLBACK_LIMIT} 条，
 *       并写回对应 重排分数（不再按 minScore 丢弃）
 */
@Slf4j
public class KnowEngineReRankingContentAggregator implements ContentAggregator {

    /**
     * minScore 过滤后结果为空时，按 RRF 排序回退返回的条数上限
     */
    private static final int EMPTY_FILTER_FALLBACK_LIMIT = 3;

    /** 交叉编码器，对 (Query, 片段) 打相关性分 */
    private final ScoringModel scoringModel;
    /** 从多 Query 的入参 Map 中选出 重排打分用的那一个 Query */
    private final Function<Map<Query, Collection<List<Content>>>, Query> querySelector;
    /** 重排分数下限；null 表示不过滤 */
    private final Double minScore;
    /** 最终返回条数上限；构造时未传则视为不截断 */
    private final Integer maxResults;

    public KnowEngineReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults) {
        this.scoringModel = ensureNotNull(scoringModel, "scoringModel");
        this.querySelector = ensureNotNull(querySelector, "querySelector");
        this.minScore = minScore;
        this.maxResults = getOrDefault(maxResults, Integer.MAX_VALUE);
    }

    public static ReRankingContentAggregatorBuilder builder() {
        return new ReRankingContentAggregatorBuilder();
    }

    /**
     * 把多路召回融合成一份候选，再用评分模型重排后返回，供后续注入 LLM 提示词
     * 入参形态 Map&lt;Query, Collection&lt;List&lt;Content&gt;&gt;&gt;：
     *   key：一次检索使用的 Query；当前流水线查询改写只产出 1 个 Query
     *   value：该 Query 下「每一路检索器各自返回的有序列表」
     *   本项目通常是 2 路：向量召回一份、关键词召回一份
     *
     * 三个阶段：
     *   选出 重排打分用的基准 Query
     *   多路 RRF：将各检索源有序列表一次性交给 {@link KnowEngineReciprocalRankFuser}
     *       按 {@code EMBEDDING_ID} 判等，同一 chunk 合并为一条并累加 RRF 分，同时合并检索来源
     *   重打分 → 按分降序 → minScore 过滤 → maxResults 截断；
     *       过滤为空时按 RRF 顺序回退前 {@link #EMPTY_FILTER_FALLBACK_LIMIT} 条
     *
     * @param queryToContents 检索结果；key 为 Query，value 为该 Query 下各检索源返回的 Content 列表集合
     * @return 重排并过滤后的 Content 列表；仅当 RRF 候选也为空时返回空列表，不会为 null
     */
    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {

        if (queryToContents.isEmpty()) {
            return emptyList();
        }

        // 1：选择重排序基准 Query（由调用方通过 querySelector 指定）
        Query query = querySelector.apply(queryToContents);

        // 2：多路 RRF——把各检索源列表（当前通常为向量 + 关键词）一次性融合
        // 使用 KnowEngineDefaultContent 按 EMBEDDING_ID 判等，双路命中同一片段会合并为一条并累加分、合并来源
        List<Content> fusedContents = fuse(queryToContents);

        if (fusedContents.isEmpty()) {
            return fusedContents;
        }

        // 3：使用 ScoringModel 对候选集重新打分，按分数降序排列，并过滤低于 minScore 的结果，截取 maxResults 条
        return reRankAndFilter(fusedContents, query);
    }

    /**
     * 多路 RRF：将入参中每一路检索器返回的有序列表，交给 {@link KnowEngineReciprocalRankFuser} 一次融合
     * 当前流水线只有 1 个 Query、2 路检索器，因此入参展平后通常是 {@code [向量列表, 关键词列表]}
     * 不使用 LangChain4j 默认 {@code ReciprocalRankFuser}：其对 {@code DefaultContent} 的判等会因
     * {@code RETRIEVAL_SOURCE} 不同而把同一片段当成两条，无法正确累加 RRF 分与合并来源
     */
    protected List<Content> fuse(Map<Query, Collection<List<Content>>> queryToContents) {
        List<List<KnowEngineDefaultContent>> listsOfContents = queryToContents.values().stream()
                .flatMap(Collection::stream)
                .map(list -> list.stream()
                        .map(content -> new KnowEngineDefaultContent((DefaultContent) content))
                        .toList())
                .toList();

        if (listsOfContents.isEmpty()) {
            return emptyList();
        }

        return KnowEngineReciprocalRankFuser.fuse(listsOfContents);
    }

    /**
     * 用 ScoringModel 对融合后的候选重新打分，再过滤、截断
     * 注意点：
     *   评分模型接收 {@link TextSegment} 文本，不是 Content 对象，所以先抽出 segment 批量 {@code scoreAll}
     *   {@code putIfAbsent}：同一 TextSegment 若出现多次，保留第一次对应的原始 Content（及其元数据）
     *   写出时把 重排分数写入 {@code RERANKED_SCORE}，并拷贝原 Content 元数据，供前端引用展示
     *   若 minScore 过滤后为空，按 RRF 顺序回退取前 {@link #EMPTY_FILTER_FALLBACK_LIMIT} 条，并写回 重排分
     *
     * @param contents 多路 RRF 之后的候选（已按 RRF 分降序）
     * @param query    阶段一选出的、给 重排模型当「问题」用的 Query
     */
    protected List<Content> reRankAndFilter(List<Content> contents, Query query) {

        // TextSegment → 原始 Content，便于打分后把元数据带回去
        Map<TextSegment, Content> segmentToOriginal = new HashMap<>();
        for (Content content : contents) {
            segmentToOriginal.putIfAbsent(content.textSegment(), content);
        }

        List<TextSegment> segments = contents.stream().map(Content::textSegment).collect(Collectors.toList());

        // 一次批量打分，避免对每个片段单独调模型
        List<Double> scores = scoringModel.scoreAll(segments, query.text()).content();

        Map<TextSegment, Double> segmentToScore = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            segmentToScore.put(segments.get(i), scores.get(i));
        }

        // 低于 minScore 的丢掉 → 按 重排分从高到低 → 写回分数元数据 → 截取 maxResults
        List<Content> filtered = segmentToScore.entrySet().stream()
                .filter(entry -> minScore == null || entry.getValue() >= minScore)
                .sorted(Map.Entry.<TextSegment, Double>comparingByValue().reversed())
                .map(entry -> {
                    Content original = segmentToOriginal.get(entry.getKey());
                    return Content.from(entry.getKey(),
                            RetrievalSourceUtil.copyMetadataWithRerankScore(original, entry.getValue()));
                })
                .limit(maxResults)
                .collect(Collectors.toList());

        if (!filtered.isEmpty()) {
            return filtered;
        }

        // 保护分支：过滤后为空时，按 RRF 顺序取前 N 条，写回已有 重排分（不再按 minScore 丢弃）
        log.warn("Rerank minScore filter emptied results (minScore={}, candidates={}), fallback to RRF top {}",
                minScore, contents.size(), EMPTY_FILTER_FALLBACK_LIMIT);
        return contents.stream()
                .limit(EMPTY_FILTER_FALLBACK_LIMIT)
                .map(content -> {
                    TextSegment segment = content.textSegment();
                    Double score = segmentToScore.get(segment);
                    return Content.from(segment, RetrievalSourceUtil.copyMetadataWithRerankScore(content, score));
                })
                .collect(Collectors.toList());
    }

    /**
     * 建造器；ChatService 典型配置：scoringModel 为百炼重排模型，maxResults=5，
     * querySelector 取 Map 中第一个 Query
     */
    public static class ReRankingContentAggregatorBuilder {
        private ScoringModel scoringModel;
        private Function<Map<Query, Collection<List<Content>>>, Query> querySelector;
        private Double minScore;
        private Integer maxResults;

        ReRankingContentAggregatorBuilder() {
        }

        /** 重排打分模型，必填 */
        public ReRankingContentAggregatorBuilder scoringModel(ScoringModel scoringModel) {
            this.scoringModel = scoringModel;
            return this;
        }

        /** 选出 重排打分用的 Query，必填 */
        public ReRankingContentAggregatorBuilder querySelector(Function<Map<Query, Collection<List<Content>>>, Query> querySelector) {
            this.querySelector = querySelector;
            return this;
        }

        /** 重排分数下限，低于则丢弃；null 表示不过滤 */
        public ReRankingContentAggregatorBuilder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        /** 最终最多返回几条；不设则不截断 */
        public ReRankingContentAggregatorBuilder maxResults(Integer maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public KnowEngineReRankingContentAggregator build() {
            return new KnowEngineReRankingContentAggregator(this.scoringModel, this.querySelector, this.minScore, this.maxResults);
        }
    }
}