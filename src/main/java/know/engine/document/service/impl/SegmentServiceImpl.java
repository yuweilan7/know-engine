package know.engine.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import know.engine.document.entity.KnowledgeSegment;
import know.engine.document.mapper.SegmentMapper;
import know.engine.document.service.SegmentService;
import org.springframework.stereotype.Service;

@Service
public class SegmentServiceImpl extends ServiceImpl<SegmentMapper, KnowledgeSegment> implements SegmentService {
    @Override
    public String getParentText(long docId, long versionId, String chunkId) {
        // 同一版本的父块才可以补充上下文。chunk_id 唯一索引定位记录。
        KnowledgeSegment parent = lambdaQuery().eq(KnowledgeSegment::getChunkId, chunkId)
                .eq(KnowledgeSegment::getDocumentId, docId)
                .eq(KnowledgeSegment::getVersionId, versionId)
                .eq(KnowledgeSegment::getSkipEmbedding, 1).one();
        return parent == null ? null : parent.getText();
    }
}
