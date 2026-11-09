package know.engine.rag;

import know.engine.chat.constant.RetrievalSource;
import know.engine.chat.entity.ChatMessage.RagReference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检索命中、融合候选和模型上下文。每个类型只持有对应阶段的信息。 */
public final class RagContext {
    /** RRF 平滑常数。值越大，各名次的贡献越接近，值越小，靠前结果的权重越高。 */
    private static final int RRF_K = 60;

    private RagContext() {}

    /** 版本切换后同一文档的同名分块仍是不同候选。ES 的 _id 另存，由检索适配器校验。 */
    public record ChunkKey(long docId, long versionId, String chunkId) {}

    public record Hit(ChunkKey key, String indexId, String text, String parentId, String title, String url,
                      int rank, Double score) {}

    /** SUCCESS 可以是空结果。关闭的检索路和调用失败的检索路都保留独立状态。 */
    public enum Availability { SUCCESS, DISABLED, UNAVAILABLE }

    public record Branch(Availability availability, List<Hit> hits) {
        public static Branch successful(List<Hit> hits) { return new Branch(Availability.SUCCESS, hits); }
        public static Branch disabled() { return new Branch(Availability.DISABLED, List.of()); }
        public static Branch unavailable() { return new Branch(Availability.UNAVAILABLE, List.of()); }
    }

    public record Candidate(Hit keyword, Hit vector, Double rerankScore) {
        public Hit hit() { return keyword != null ? keyword : vector; }
        public ChunkKey key() { return hit().key(); }
        public double rrfScore() { return contribution(keyword) + contribution(vector); }
        private static double contribution(Hit hit) { return hit == null ? 0 : 1.0 / (RRF_K + hit.rank()); }

        RagReference reference(String contextId, String contextContent) {
            Hit hit = hit();
            return RagReference.builder().documentId(Long.toString(key().docId()))
                    .versionId(Long.toString(key().versionId())).chunkId(key().chunkId())
                    .parentChunkId(hit.parentId()).chunkContent(hit.text()).documentTitle(hit.title()).url(hit.url())
                    .contextId(contextId).contextContent(contextContent)
                    .similarityScore(hit.score()).rrfScore(rrfScore()).rerankScore(rerankScore)
                    .keywordRank(keyword == null ? null : keyword.rank()).keywordScore(keyword == null ? null : keyword.score())
                    .vectorRank(vector == null ? null : vector.rank()).vectorScore(vector == null ? null : vector.score())
                    .retrievalSource(keyword == null ? RetrievalSource.VECTOR
                            : vector == null ? RetrievalSource.KEYWORD : RetrievalSource.HYBRID)
                    .build();
        }
    }

    public static final Comparator<Candidate> RRF_ORDER = Comparator.comparingDouble(Candidate::rrfScore).reversed()
            .thenComparing(c -> c.key().docId()).thenComparing(c -> c.key().versionId())
            .thenComparing(c -> c.key().chunkId());

    /**
     * Reciprocal Rank Fusion（RRF）按单路排名累加 {@code 1 / (k + rank)}，排名从 1 开始。
     * 两路命中相同 ChunkKey 时合并为一个候选，记录两个名次和原始分，来源展示为 HYBRID。
     * 向量分和关键词 BM25 分各自保留。融合分相同时按文档、版本和分块 ID 排序。
     *
     * @param keyword 关键词路按 ES 返回顺序排列的候选
     * @param vector 向量路按 ES 返回顺序排列的候选
     * @return 按融合分降序排列的候选，供重排或降级时直接使用
     */
    public static List<Candidate> fuse(Branch keyword, Branch vector) {
        Map<ChunkKey, Candidate> candidates = new LinkedHashMap<>();
        addBranch(candidates, keyword, false);
        addBranch(candidates, vector, true);
        return candidates.values().stream().sorted(RRF_ORDER).toList();
    }

    private static void addBranch(Map<ChunkKey, Candidate> candidates, Branch branch, boolean vector) {
        var seen = new HashSet<ChunkKey>();
        for (Hit hit : branch.hits()) {
            if (!seen.add(hit.key())) continue; // 单路重复只使用第一次命中的名次。
            candidates.compute(hit.key(), (key, previous) -> vector
                    ? new Candidate(previous == null ? null : previous.keyword(), hit, null)
                    : new Candidate(hit, null, null));
        }
    }

    /** text 是实际注入模型的文本。childKeys 指向参与打分的子块，一个父块可对应多个命中。 */
    public record ContextItem(String contextId, String text, ChunkKey parent, List<ChunkKey> childKeys) {}

    public record Prepared(String query, List<ContextItem> items, List<RagReference> references,
                           int inputByteLimit, int usedInputBytes, boolean approximate) {
        public String contents() { return String.join("\n\n", items.stream().map(ContextItem::text).toList()); }
    }

    public record ParentText(String text, boolean unavailable) {}

    @FunctionalInterface
    public interface Parents {
        ParentText get(ChunkKey parent);
    }

    public record Selection(List<ContextItem> items, List<RagReference> references, int parentFallbacks) {}

    /**
     * 按子块排序依次尝试扩展父块，读取失败或预算放不下时尝试原子块。
     * 只有真正选入的父块才去重，即使上下文已满，仍收集这些父块对应的后续子块引用。
     */
    public static Selection select(List<Candidate> ordered, Parents parents, int availableBytes) {
        var items = new ArrayList<Selected>();
        var expanded = new LinkedHashMap<ChunkKey, Selected>();
        var parentResults = new LinkedHashMap<ChunkKey, ParentText>();
        int fallbackCount = 0;
        for (Candidate candidate : ordered) {
            Hit hit = candidate.hit();
            ChunkKey parent = hit.parentId() == null ? null
                    : new ChunkKey(hit.key().docId(), hit.key().versionId(), hit.parentId());
            Selected existing = expanded.get(parent);
            if (existing != null) {
                existing.matches.add(candidate);
                continue;
            }
            if (items.size() == 5 || availableBytes <= 0) continue;
            int separatorBytes = items.isEmpty() ? 0 : 2;
            ParentText found = parent == null ? null : parentResults.computeIfAbsent(parent, parents::get);
            String parentText = found == null ? null : found.text();
            boolean useParent = parentText != null && !parentText.isBlank()
                    && PromptBudget.bytes(parentText) + separatorBytes <= availableBytes;
            if (parent != null && !useParent) fallbackCount++;
            String text = useParent ? parentText : hit.text();
            int cost = PromptBudget.bytes(text) + separatorBytes;
            if (cost > availableBytes) continue;
            availableBytes -= cost;
            var item = new Selected("context-" + (items.size() + 1), text, useParent ? parent : null,
                    new ArrayList<>(List.of(candidate)));
            items.add(item);
            if (useParent) expanded.put(parent, item);
        }
        var references = new ArrayList<RagReference>();
        for (Selected item : items) {
            for (int i = 0; i < item.matches.size(); i++) {
                references.add(item.matches.get(i).reference(item.id, i == 0 ? item.text : null));
            }
        }
        return new Selection(items.stream().map(item -> new ContextItem(item.id, item.text, item.parent,
                        item.matches.stream().map(Candidate::key).toList())).toList(), List.copyOf(references), fallbackCount);
    }

    private record Selected(String id, String text, ChunkKey parent, List<Candidate> matches) {}
}
