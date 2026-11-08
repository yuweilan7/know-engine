package know.engine.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import know.engine.document.entity.Document;

import java.util.List;

public interface DocumentService extends IService<Document> {
    List<Long> activeVersionIds();
    boolean removeDocumentWithSegments(Long docId);
    boolean removeDocumentsWithSegments(List<Long> docIds);
}
