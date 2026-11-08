package know.engine.document.service;

import know.engine.document.entity.KnowledgeSegment;

import java.util.List;

public interface VectorStoreService {
    void embedAndStore(List<KnowledgeSegment> segments, String title, String sourceUrl);
    void verifySearchable(long docId, long versionId, int expected);
    void removeByDocId(long docId);
}
