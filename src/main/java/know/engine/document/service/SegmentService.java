package know.engine.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import know.engine.document.entity.KnowledgeSegment;

public interface SegmentService extends IService<KnowledgeSegment> {
    String getParentText(long docId, long versionId, String chunkId);
}
