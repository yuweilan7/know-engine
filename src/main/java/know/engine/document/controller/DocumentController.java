package know.engine.document.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import know.engine.common.DefaultUser;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentSplitParam;
import know.engine.document.entity.DocumentUploadParam;
import know.engine.document.entity.DocumentVersion;
import know.engine.document.service.DocumentProcessService;
import know.engine.document.service.DocumentService;
import know.engine.document.service.DocumentVersionService;
import know.engine.document.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentService documents;
    private final DocumentVersionService versions;
    private final DocumentProcessService process;
    private final FileStorageService storage;

    @PostMapping("/upload")
    public DocumentVersion upload(@RequestParam MultipartFile file, @RequestParam String title,
                                  @RequestParam(defaultValue = "1.0.0") String version,
                                  @RequestParam(defaultValue = "") String description,
                                  @RequestParam(required = false) String uploadUser) throws IOException {
        return process.upload(new DocumentUploadParam(file, title, description, version),
                DefaultUser.userNameOrDefault(uploadUser));
    }

    @PostMapping("/upload-version")
    public DocumentVersion uploadVersion(@RequestParam MultipartFile file, @RequestParam Long docId,
                                         @RequestParam String version,
                                         @RequestParam(required = false) String changelog,
                                         @RequestParam(required = false) String uploadUser) throws IOException {
        return process.uploadNewVersion(docId, version, file, DefaultUser.userNameOrDefault(uploadUser), changelog);
    }

    @GetMapping("/versions/{docId}")
    public List<VersionView> listVersions(@PathVariable Long docId) {
        Document doc = documents.getById(docId);
        if (doc == null) throw new IllegalArgumentException("文档不存在");
        return versions.listByDocId(docId).stream()
                .map(v -> new VersionView(v, v.getVersionId().equals(doc.getActiveVersionId()),
                        storage.objectUrl(v.getSourceObjectKey()))).toList();
    }

    public record VersionView(DocumentVersion revision, boolean active, String docUrl) {}

    @PostMapping("/split/{docId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void split(@PathVariable Long docId, @RequestParam Long versionId, @RequestParam String splitType,
                      @RequestParam Integer chunkSize, @RequestParam(required = false) Integer overlap,
                      @RequestParam(required = false) String regex, @RequestParam(required = false) Integer titleLevel,
                      @RequestParam(required = false) String separator) {
        DocumentVersion version = versions.getById(versionId);
        if (version == null || !docId.equals(version.getDocId())) throw new IllegalArgumentException("版本不属于该文档");
        process.requestBuild(versionId, new DocumentSplitParam(splitType, chunkSize, overlap, titleLevel, separator, regex));
    }

    @PostMapping("/versions/{versionId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable Long versionId) {
        process.retry(versionId);
    }

    @PostMapping("/switch-version")
    public Document switchVersion(@RequestParam Long docId, @RequestParam Long versionId) {
        return process.switchVersion(docId, versionId);
    }

    @GetMapping("/page")
    public Page<Document> page(@RequestParam(defaultValue = "1") Integer current,
                               @RequestParam(defaultValue = "10") Integer size,
                               @RequestParam(required = false) String docTitle,
                               @RequestParam(required = false) String status) {
        return documents.lambdaQuery()
                .like(docTitle != null && !docTitle.isBlank(), Document::getDocTitle, docTitle)
                .isNotNull("ACTIVE".equals(status), Document::getActiveVersionId)
                .isNull("DRAFT".equals(status), Document::getActiveVersionId)
                .orderByDesc(Document::getCreatedAt, Document::getDocId)
                .page(new Page<>(Math.max(current, 1), Math.max(1, Math.min(size, 200))));
    }

    @GetMapping("/{id:\\d+}")
    public Document get(@PathVariable Long id) {
        return documents.getById(id);
    }

    /** 编辑页面只能改文档基本信息。生效版本只能从统一发布入口切换。 */
    public record EditDocument(Long docId, Integer lockVersion, String docTitle, String description) {}

    @PutMapping
    public boolean update(@RequestBody EditDocument edit) {
        if (edit.docId() == null || edit.lockVersion() == null || edit.docTitle() == null || edit.docTitle().isBlank()) {
            throw new IllegalArgumentException("请输入标题和编辑修订号");
        }
        Document doc = new Document();
        doc.setDocId(edit.docId());
        doc.setLockVersion(edit.lockVersion());
        doc.setDocTitle(edit.docTitle());
        doc.setDescription(edit.description());
        if (!documents.updateById(doc)) throw new IllegalArgumentException("文档已改变，请刷新后再编辑");
        return true;
    }

    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return documents.removeDocumentWithSegments(id);
    }

    @DeleteMapping("/batch")
    public boolean deleteBatch(@RequestParam List<Long> ids) {
        return documents.removeDocumentsWithSegments(ids);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
