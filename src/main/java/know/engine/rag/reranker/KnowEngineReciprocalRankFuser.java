package know.engine.rag.reranker;

import know.engine.chat.constant.RetrievalSource;
import know.engine.rag.KnowEngineReRankingContentAggregator;
import know.engine.rag.util.RetrievalSourceUtil;
import dev.langchain4j.rag.content.Content;

import java.util.*;

import static dev.langchain4j.internal.ValidationUtils.ensureBetween;

/**
 * Reciprocal Rank Fusion（RRF）实现，用于 RAG 管道中的多路召回融合
 * 与 LangChain4j 自带的 {@link dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser} 相比，本类额外处理两件事：
 *   借助 {@link KnowEngineDefaultContent} 按 {@code EMBEDDING_ID} 判等
 *       将同一知识片段在不同检索源（向量 / 关键词）下的多次出现合并为一条，并累加 RRF 分
 *   通过 {@link RetrievalSourceUtil} 合并检索来源元数据（如 {@code VECTOR} + {@code KEYWORD} → {@code HYBRID}）
 * RRF 公式：{@code score(d) += 1 / (k + rank)}，其中 {@code rank} 为条目在单路有序列表中的 1-based 排名
 *
 * @see KnowEngineDefaultContent
 * @see KnowEngineReRankingContentAggregator#aggregate
 */
public class KnowEngineReciprocalRankFuser {

    /** RRF 平滑常数默认值，与 LangChain4j {@code ReciprocalRankFuser} 及业界经验值一致 */
    private static final int DEFAULT_K = 60;

    private KnowEngineReciprocalRankFuser() {
    }

    /**
     * 使用默认 {@code k = 60} 对多路有序列表做 RRF 融合
     */
    public static List<Content> fuse(Collection<List<KnowEngineDefaultContent>> listsOfContents) {
        return fuse(listsOfContents, DEFAULT_K);
    }

    /**
     * 使用指定 {@code k} 对多路有序列表做 RRF 融合
     * 遍历每路列表，按排名累加 {@code 1 / (k + rank)} 到对应 {@link Content} 的融合分；
     * 相同 {@code EMBEDDING_ID} 的条目因 {@link KnowEngineDefaultContent} 的相等性语义被视为同一条，分数会叠加
     *
     * @param listsOfContents 多路有序候选列表的集合
     * @param k               RRF 平滑常数，须 {@code >= 1}值越大，各排名之间的分差越平缓；
     *                        值越小，各路 Top 结果的权重越高常用值为 60
     * @return 按融合分降序排列的 {@link Content} 列表，检索来源元数据已合并
     */
    public static List<Content> fuse(Collection<List<KnowEngineDefaultContent>> listsOfContents, int k) {
        ensureBetween(k, 1, Integer.MAX_VALUE, "k");

        Map<Content, Double> scores = new LinkedHashMap<>();
        Map<Content, RetrievalSource> contentSources = new LinkedHashMap<>();
        for (List<KnowEngineDefaultContent> singleListOfContent : listsOfContents) {
            for (int i = 0; i < singleListOfContent.size(); i++) {
                Content content = singleListOfContent.get(i);
                double currentScore = scores.getOrDefault(content, 0.0);
                int rank = i + 1;
                double newScore = currentScore + 1.0 / (k + rank);
                scores.put(content, newScore);
                contentSources.merge(content, RetrievalSourceUtil.resolveFromContent(content), RetrievalSourceUtil::merge);
            }
        }

        List<Content> fused = new ArrayList<>(scores.keySet());
        fused.sort(Comparator.comparingDouble(scores::get).reversed());
        return fused.stream()
                .map(content -> RetrievalSourceUtil.withMergedSource(content, contentSources.get(content)))
                .toList();
    }
}