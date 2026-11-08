package know.engine.eval.metrics;

import know.engine.chat.entity.ChatMessage;
import know.engine.eval.model.EvalCase;
import know.engine.eval.model.RetrievalScores;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索层指标计算器：Hit@K、MRR、Recall@K
 * <p>
 * 分片级比对时，检索结果的 chunkId 或其 parentChunkId 任一落在 Ground Truth 中即视为命中，
 * 以兼容 GT 标注父分块、检索返回子分块的场景。
 */
@Component
public class RetrievalMetricCalculator {

    /**
     * 按 Ground Truth 计算检索指标。
     *
     * @param retrievedReferences  检索引用（按相关度排序），分片级评测优先使用
     * @param retrievedDocumentIds 检索到的文档 ID（按首次出现顺序）
     * @param evalCase             评测用例
     * @param topK                 Top-K
     */
    public RetrievalScores calculate(List<ChatMessage.RagReference> retrievedReferences,
                                     List<String> retrievedDocumentIds,
                                     EvalCase evalCase,
                                     int topK) {
        Set<String> gtChunks = toSet(evalCase.getGroundTruthChunkIds());
        Set<String> gtDocs = toSet(evalCase.getGroundTruthDocumentIds());

        List<ChatMessage.RagReference> topRefs = sliceTop(retrievedReferences, topK);
        List<String> topDocs = sliceTop(retrievedDocumentIds, topK);

        boolean useChunkLevel = !gtChunks.isEmpty();
        boolean useDocLevel = !useChunkLevel && !gtDocs.isEmpty();

        if (!useChunkLevel && !useDocLevel) {
            return new RetrievalScores(false, 0.0, 0.0);
        }

        boolean hitAtK = useChunkLevel
                ? topRefs.stream().anyMatch(ref -> matchesChunkGroundTruth(ref, gtChunks))
                : topDocs.stream().anyMatch(gtDocs::contains);

        double mrr = useChunkLevel
                ? computeChunkMrr(topRefs, gtChunks)
                : computeMrr(topDocs, gtDocs);

        double recallAtK = useChunkLevel
                ? computeChunkRecall(topRefs, gtChunks)
                : computeRecall(topDocs, gtDocs);

        return new RetrievalScores(hitAtK, mrr, recallAtK);
    }

    private boolean matchesChunkGroundTruth(ChatMessage.RagReference ref, Set<String> groundTruth) {
        if (ref == null || groundTruth.isEmpty()) {
            return false;
        }
        if (StringUtils.hasText(ref.getChunkId()) && groundTruth.contains(ref.getChunkId())) {
            return true;
        }
        return StringUtils.hasText(ref.getParentChunkId()) && groundTruth.contains(ref.getParentChunkId());
    }

    private double computeChunkMrr(List<ChatMessage.RagReference> retrieved, Set<String> groundTruth) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (matchesChunkGroundTruth(retrieved.get(i), groundTruth)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double computeChunkRecall(List<ChatMessage.RagReference> retrieved, Set<String> groundTruth) {
        Set<String> hitGt = new HashSet<>();
        for (ChatMessage.RagReference ref : retrieved) {
            if (ref == null) {
                continue;
            }
            if (StringUtils.hasText(ref.getChunkId()) && groundTruth.contains(ref.getChunkId())) {
                hitGt.add(ref.getChunkId());
            }
            if (StringUtils.hasText(ref.getParentChunkId()) && groundTruth.contains(ref.getParentChunkId())) {
                hitGt.add(ref.getParentChunkId());
            }
        }
        return (double) hitGt.size() / groundTruth.size();
    }

    private double computeMrr(List<String> retrieved, Set<String> groundTruth) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (groundTruth.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double computeRecall(List<String> retrieved, Set<String> groundTruth) {
        long hitCount = retrieved.stream().filter(groundTruth::contains).distinct().count();
        return (double) hitCount / groundTruth.size();
    }

    private <T> List<T> sliceTop(List<T> ids, int topK) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.subList(0, Math.min(topK, ids.size()));
    }

    private Set<String> toSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(values);
    }
}