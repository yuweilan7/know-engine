package know.engine.document.service;

import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentSplitParam;
import know.engine.document.entity.DocumentUploadParam;
import know.engine.document.entity.DocumentVersion;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface DocumentProcessService {
    DocumentVersion upload(DocumentUploadParam param, String uploadUser) throws IOException;
    DocumentVersion uploadNewVersion(Long docId, String version, MultipartFile file, String uploadUser,
                                     String changelog, DocumentSplitParam split) throws IOException;
    void requestBuild(long versionId, DocumentSplitParam params);
    void retry(long versionId);
    Document switchVersion(Long docId, Long versionId);
    void advanceVersion(long versionId);
}
