package know.engine.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import know.engine.document.entity.Document;
import know.engine.document.mapper.DocumentMapper;
import know.engine.document.service.DocumentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements DocumentService {
    @Override
    public List<Long> activeVersionIds() {
        return baseMapper.activeVersionIds();
    }

    @Override
    @Transactional
    public boolean removeDocumentWithSegments(Long docId) {
        Document document = baseMapper.lockLive(docId);
        if (document == null) return false;
        // 先撤下生效版。补偿任务之后按这个文档 ID 清理 ES 和文件。
        baseMapper.retract(docId);
        return true;
    }

    @Override
    @Transactional
    public boolean removeDocumentsWithSegments(List<Long> docIds) {
        boolean changed = false;
        for (Long id : docIds.stream().distinct().sorted().toList()) {
            changed |= removeDocumentWithSegments(id);
        }
        return changed;
    }
}
