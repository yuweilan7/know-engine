package know.engine.chat.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import know.engine.chat.constant.RetrievalSource;
import know.engine.rag.PromptBudget;
import know.engine.rag.RagContext;
import know.engine.rag.RagContext.Branch;
import know.engine.rag.RagContext.ChunkKey;
import know.engine.rag.RagContext.Hit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagContextTest {
    @Test
    void fusionKeepsScoresAndVersionIdentityAndCountsOnlyFirstMatchPerLane() {
        var a = new ChunkKey(1, 1, "a");
        var otherVersion = new ChunkKey(1, 2, "a");
        var b = new ChunkKey(2, 1, "b");
        var fused = RagContext.fuse(
                Branch.successful(List.of(hit(b, null, "b", 1, 3.0), hit(a, null, "a", 2, 1.5), hit(a, null, "a", 3, 1.0))),
                Branch.successful(List.of(hit(a, null, "a", 1, 0.9), hit(otherVersion, null, "a", 2, 0.8))));
        assertEquals(List.of(a, b, otherVersion), fused.stream().map(RagContext.Candidate::key).toList());
        assertEquals(1.0 / 61 + 1.0 / 62, fused.getFirst().rrfScore(), 1e-10);
        var refs = RagContext.select(fused, ignored -> new RagContext.ParentText(null, false), 20).references();
        assertEquals(RetrievalSource.HYBRID, refs.getFirst().getRetrievalSource());
        assertEquals(1.5, refs.getFirst().getKeywordScore());
        assertEquals(0.9, refs.getFirst().getVectorScore());
    }

    @Test
    void oneSelectedParentRetainsSiblingEvidenceAndDoesNotConsumeAllSlots() {
        var hits = List.of(hit(new ChunkKey(1, 1, "a"), "parent", "a", 1, 2.0),
                hit(new ChunkKey(1, 1, "b"), "parent", "b", 2, 1.0),
                hit(new ChunkKey(2, 2, "c"), "parent", "c", 3, 1.0),
                hit(new ChunkKey(2, 2, "d"), "parent", "d", 4, 1.0));
        var context = RagContext.select(RagContext.fuse(Branch.successful(hits), Branch.disabled()),
                key -> new RagContext.ParentText(key.docId() == 1 ? "实际父块" : "x".repeat(500), false), 40);
        assertEquals(3, context.items().size());
        assertEquals("实际父块", context.items().getFirst().text());
        assertEquals(List.of(hits.get(0).key(), hits.get(1).key()), context.items().getFirst().childKeys());
        assertEquals(List.of("a", "b", "c", "d"), context.references().stream().map(ref -> ref.getChunkContent()).toList());
        assertEquals(context.references().get(0).getContextId(), context.references().get(1).getContextId());
        assertEquals("c", context.items().get(1).text());
        assertEquals("d", context.items().get(2).text());
    }

    @Test
    void budgetTrimsOldestCompletePairAndCountsUtf8AndPromptOverhead() {
        var latest = List.of(UserMessage.from("新"), AiMessage.from("答"));
        var fit = PromptBudget.fit(285, "系统", "问题", List.of(
                UserMessage.from("旧".repeat(100)), AiMessage.from("旧答"), latest.get(0), latest.get(1)));
        assertEquals(latest, fit.history());
        assertEquals(274, fit.usedBytes());
        assertEquals(11, fit.availableBytes());
    }

    private static Hit hit(ChunkKey key, String parent, String text, int rank, double score) {
        return new Hit(key, key.chunkId(), text, parent, null, null, rank, score);
    }
}
