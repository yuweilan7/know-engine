package know.engine.document.controller;

import know.engine.document.entity.DocumentSplitParam;
import know.engine.document.entity.DocumentUploadParam;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentVersion;
import know.engine.common.DefaultUser;
import know.engine.document.service.DocumentProcessService;
import know.engine.document.service.DocumentService;
import know.engine.document.service.DocumentVersionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识文档表 Controller
 */
@RestController
@RequestMapping("/api/document")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentVersionService documentVersionService;

    @Autowired
    private DocumentProcessService documentProcessService;

    /**
     * 文件上传接口
     *
     * @param file       上传的文件
     * @param uploadUser 上传人（可选，不传则使用默认用户）
     * @return 保存后的文档记录
     */
    @PostMapping("/upload")
    public Document uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "version", required = false, defaultValue = "1.0.0") String version,
            @RequestParam("description") String description,
            @RequestParam(value = "uploadUser", required = false) String uploadUser) throws IOException {
        return documentProcessService.upload(new DocumentUploadParam(file, title, description, version),
                DefaultUser.userNameOrDefault(uploadUser));
    }

    /**
     * 上传文档新版本
     *
     * @param file       新版本文件
     * @param docId      文档ID（knowledge_document.doc_id）
     * @param version    新版本号（语义化版本，如 "2.0.0"，必须大于现有最新版本号）
     * @param changelog  版本变更说明（可选）
     * @param uploadUser 上传人（可选，不传则使用默认用户）
     * @return 更新后的文档记录
     */
    @PostMapping("/upload-version")
    public Document uploadVersion(
            @RequestParam("file") MultipartFile file,
            @RequestParam("docId") Long docId,
            @RequestParam("version") String version,
            @RequestParam(value = "changelog", required = false) String changelog,
            @RequestParam(value = "uploadUser", required = false) String uploadUser) throws IOException {
        return documentProcessService.uploadNewVersion(docId, version, file, DefaultUser.userNameOrDefault(uploadUser), changelog);
    }

    /**
     * 查询文档的所有版本（按版本号降序）
     *
     * @param docId 文档ID
     * @return 版本列表
     */
    @GetMapping("/versions/{docId}")
    public List<DocumentVersion> listVersions(@PathVariable Long docId) {
        return documentVersionService.listByDocId(docId);
    }

    /**
     * 切换文档到指定版本
     * 清理当前版本的分段和向量，恢复目标版本的文件URL和状态，状态置为 CONVERTED 等待重新切片
     *
     * @param docId     文档ID
     * @param versionId 目标版本ID
     * @return 更新后的文档记录
     */
    @PostMapping("/switch-version")
    public Document switchVersion(@RequestParam("docId") Long docId, @RequestParam("versionId") Long versionId) {
        return documentProcessService.switchVersion(docId, versionId);
    }

    /**
     * 让指定版本失效：清理该版本 ES 向量，将分段状态降为 STORED，版本状态降为 CHUNKED
     *
     * @param versionId 版本ID（knowledge_document_version.version_id）
     */
    @PostMapping("/deactivate-version")
    public void deactivateVersion(@RequestParam("versionId") Long versionId) {
        documentService.deactivateVersion(versionId);
    }

    /**
     * 让指定版本生效（重新向量化）：对 STORED 分段重新 embed 写入 ES，版本状态升为 VECTOR_STORED
     *
     * @param versionId 版本ID（knowledge_document_version.version_id）
     */
    @PostMapping("/activate-version")
    public void activateVersion(@RequestParam("versionId") Long versionId) {
        documentService.activateVersion(versionId);
    }

    /**
     * 对文档进行切分
     * 注意：上传仅转换到 CONVERTED，切片需手动触发；切片成功后由 DocumentChunkedEvent 异步向量化
     *
     * @param documentId 文档ID
     * @return 切分后的片段数量
     */
    @PostMapping("/split/{documentId}")
    public Integer splitDocument(@PathVariable Long documentId,
                                 @RequestParam("splitType") String splitType,
                                 @RequestParam("chunkSize") Integer chunkSize,
                                 @RequestParam(value = "overlap", required = false) Integer overlap,
                                 @RequestParam(value = "regex", required = false) String regex,
                                 @RequestParam(value = "titleLevel", required = false) Integer titleLevel,
                                 @RequestParam(value = "separator", required = false) String separator) {
        Document document = documentService.getById(documentId);
        return documentProcessService.split(document, new DocumentSplitParam(splitType, chunkSize, overlap, titleLevel, separator, regex));
    }

    /**
     * 分页查询（支持多条件筛选）
     *
     * @param current  当前页
     * @param size     每页大小
     * @param docTitle 文档标题（模糊查询，可选）
     * @param status   文档状态，可选值：UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED
     * @return 分页结果
     */
    @GetMapping("/page")
    public Page<Document> page(@RequestParam(defaultValue = "1") Integer current,
                               @RequestParam(defaultValue = "10") Integer size,
                               @RequestParam(value = "docTitle", required = false) String docTitle,
                               @RequestParam(value = "status", required = false) String status) {
        Page<Document> page = new Page<>(current, size);
        QueryWrapper<Document> wrapper = new QueryWrapper<>();
        if (docTitle != null && !docTitle.isEmpty()) {
            wrapper.like("doc_title", docTitle);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("created_at");
        return documentService.page(page, wrapper);
    }

    /**
     * 根据ID查询文档详情
     */
    @GetMapping("/{id:\\d+}")
    public Document getById(@PathVariable Long id) {
        return documentService.getById(id);
    }

    /**
     * 新增文档记录
     *
     * @param document 文档实体
     * @return 是否新增成功
     */
    @PostMapping
    public boolean save(@RequestBody Document document) {
        return documentService.save(document);
    }

    /**
     * 根据ID更新文档（需携带 lockVersion 乐观锁版本号）
     *
     * @param document 文档实体
     * @return 是否更新成功
     */
    @PutMapping
    public boolean updateById(@RequestBody Document document) {
        return documentService.updateById(document);
    }

    /**
     * 根据ID删除文档（逻辑删除，并级联删除该文档下的所有分段及向量）
     *
     * @param id 文档ID
     * @return 是否删除成功
     */
    @DeleteMapping("/{id}")
    public boolean removeById(@PathVariable Long id) {
        return documentService.removeDocumentWithSegments(id);
    }

    /**
     * 批量删除文档（逻辑删除，并级联删除分段及向量）
     *
     * @param ids 文档ID列表
     * @return 是否删除成功
     */
    @DeleteMapping("/batch")
    public boolean removeByIds(@RequestParam List<Long> ids) {
        return documentService.removeDocumentsWithSegments(ids);
    }

    /**
     * 处理文档上传中的参数异常（如内容重复）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
