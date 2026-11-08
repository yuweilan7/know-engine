package know.engine.document.service.impl;

import know.engine.document.constant.DocumentStatus;
import know.engine.document.constant.FileType;
import know.engine.document.constant.SegmentStatus;
import know.engine.document.entity.*;
import know.engine.document.event.DocumentChunkedEvent;
import know.engine.document.mapper.SegmentMapper;
import know.engine.document.service.*;
import know.engine.document.util.FileTypeUtil;
import know.engine.document.util.VersionUtil;
import know.engine.infra.lock.DistributeLock;
import know.engine.infra.snowflake.SnowflakeIdGenerator;
import know.engine.rag.constant.MetadataKeyConstant;
import know.engine.rag.splitter.DocumentSplitterFactory;
import know.engine.rag.splitter.ExcelSplitter;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.base.Stopwatch;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 文档处理服务实现类
 * 负责文档的业务流程处理：上传、转换、分段、向量化
 */
@Slf4j
@Service
public class DocumentProcessServiceImpl implements DocumentProcessService {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentVersionService documentVersionService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileProcessServiceFactory fileProcessServiceFactory;

    @Autowired
    private SegmentService segmentService;

    @Autowired
    private SegmentMapper segmentMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private DocumentCleanupService documentCleanupService;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Override
    @DistributeLock(scene = "document-upload", keyExpression = "#uploadUser", waitTime = 0)
    public Document upload(DocumentUploadParam documentUploadParam, String uploadUser) throws IOException {
        // 计算文件内容hash，用于去重
        String contentHash = calculateContentHash(documentUploadParam.file());

        // 检查是否已存在相同内容的版本（跨文档跨版本去重）
        if (documentVersionService.existsByContentHash(contentHash)) {
            throw new IllegalArgumentException("文档内容已存在，请勿重复上传");
        }

        // 创建文档记录
        Document document = new Document().create(documentUploadParam);
        boolean result = documentService.save(document);
        Assert.isTrue(result, "文件上传失败");


        log.info("start to upload ....");
        String fileName = documentUploadParam.file().getOriginalFilename();
        // 用minio上传
        String fileUrl = null;
        try {
            fileUrl = fileStorageService.uploadFile(documentUploadParam.file(), fileName);
        } catch (Exception e) {
            documentService.removeDocumentWithSegments(document.getDocId());
            log.error("文件上传失败，文档已删除，docId: {}", document.getDocId(), e);
            throw new IOException("原始文件存储失败", e);
        }

        // 创建初始版本记录
        DocumentVersion versionRecord = createVersionRecord(
                document.getDocId(), documentUploadParam.version(), fileUrl, null,
                uploadUser, contentHash, DocumentStatus.UPLOADED, null);
        document.setCurrentVersionId(versionRecord.getVersionId());

        // 处理文档（转换/存储），获取转换后的文档URL
        String convertedDocUrl = processFile(fileName, documentUploadParam.file(), document, fileUrl);

        // 更新版本记录的转换后URL
        versionRecord = documentVersionService.getById(versionRecord.getVersionId());
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        result = documentVersionService.updateById(versionRecord);
        Assert.isTrue(result, "版本记录更新失败");

        Document documentInDb = documentService.getById(document.getDocId());
        documentInDb.setCurrentVersionId(versionRecord.getVersionId());
        result = documentService.updateById(documentInDb);
        Assert.isTrue(result, "文档当前版本更新失败");

        return documentService.getById(document.getDocId());
    }

    /**
     * 处理文档（转换/存储）
     */
    private String processFile(String fileName, MultipartFile documentUploadParam, Document document, String fileUrl) throws IOException {
        String convertedDocUrl;
        FileProcessService fileProcessService = fileProcessServiceFactory.get(FileTypeUtil.getFileType(fileName));
        if (fileProcessService != null) {
            //  PDF / Word / Markdown
            convertedDocUrl = fileProcessService.processDocument(
                    document, documentUploadParam.getInputStream(), fileName);
        } else {
            //  TXT / Excel / CSV
            documentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);
            document.setStatus(DocumentStatus.CONVERTED);
            convertedDocUrl = fileUrl;
        }
        return convertedDocUrl;
    }

    @Override
    @DistributeLock(scene = "document-upload", keyExpression = "#uploadUser", waitTime = 0)
    public Document uploadNewVersion(Long docId, String version, MultipartFile file, String uploadUser, String changelog) throws IOException {
        // 查询文档
        Document document = documentService.getById(docId);
        Assert.notNull(document, "文档不存在");

        // 校验版本号必须大于已有最大版本号
        String latestVersion = documentVersionService.getLatestVersion(docId);
        if (latestVersion != null && VersionUtil.compareVersions(version, latestVersion) <= 0) {
            throw new IllegalArgumentException("版本号 " + version + " 不大于现有最新版本号 " + latestVersion + "，请使用更大的版本号");
        }

        // 计算文件内容hash，用于去重
        String contentHash = calculateContentHash(file);

        // 检查是否已存在相同内容的版本（跨文档跨版本去重）
        if (documentVersionService.existsByContentHash(contentHash)) {
            throw new IllegalArgumentException("文档内容已存在，请勿重复上传");
        }

        DocumentVersion versionRecord = null;
        log.info("start to upload version {} for doc {} ....", version, docId);

        // 1. 上传新版本文件到MinIO（不清理旧版本数据，保证处理期间旧版本仍可查询）
        String fileName = file.getOriginalFilename();
        String fileUrl = null;
        try {
            fileUrl = fileStorageService.uploadFile(file, fileName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 2. 先创建新版本记录，使 processDocument 内部可以推进版本状态
        versionRecord = createVersionRecord(document.getDocId(), version, fileUrl, null, uploadUser, contentHash, DocumentStatus.UPLOADED, changelog);
        // 这一步先不更新数据库，只是为了让后续的操作能从document中取出version，避免npm和流程走不下去
        // document的更新会在最后执行，确保前置流程都完成后实现版本的切换。
        document.setCurrentVersionId(versionRecord.getVersionId());

        // 3. 处理文档（转换/存储），获取转换后的文档URL
        String convertedDocUrl = processFile(fileName, file, document, fileUrl);

        // 4. 更新版本记录的转换后URL
        versionRecord = documentVersionService.getById(versionRecord.getVersionId());
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        boolean result = documentVersionService.updateById(versionRecord);
        Assert.isTrue(result, "版本记录更新失败");

        result = documentService.updateById(document);
        Assert.isTrue(result, "文档当前版本更新失败");

        log.info("文档 {} 新版本 {} 上传完成，旧版本数据保留中，待新版本向量化完成后自动清理", docId, version);
        return document;
    }

    @Override
    @Transactional
    @DistributeLock(scene = "document-split", keyExpression = "#document.docId", waitTime = 0)
    public int split(Document document, DocumentSplitParam documentSplitParam) {
        // 1. 查询文档
        Assert.notNull(document, "文档不存在");

        // 从版本表获取当前版本的文件URL
        DocumentVersion versionRecord = documentVersionService.getById(document.getCurrentVersionId());
        Assert.notNull(versionRecord, "文档版本不存在");
        Assert.notNull(versionRecord.getConvertedDocUrl(), "文档未转换完成");

        if (versionRecord.getStatus() == DocumentStatus.CHUNKED) {
            // 返回已切分的分段数量（仅统计当前版本的分段，排除旧版本残留）
            Long chunkedCount = segmentService.count(new QueryWrapper<KnowledgeSegment>()
                    .eq("document_id", document.getDocId())
                    .eq("document_version", document.getCurrentVersionId())
                    .eq("skipEmbedding", 0));
            return chunkedCount.intValue();
        }

        if (versionRecord.getStatus() != DocumentStatus.CONVERTED) {
            throw new RuntimeException("文档状态不为CONVERTED，无法完成切分");
        }

        // 2. 从MinIO下载文件内容（从版本表获取转换后的文档URL）
        String convertedDocUrl = versionRecord.getConvertedDocUrl();
        String objectName = extractObjectNameFromUrl(convertedDocUrl);
        Assert.notNull(objectName, "无法解析文档URL");

        List<KnowledgeSegment> knowledgeSegments = new ArrayList<>();
        List<TextSegment> segments;
        try (InputStream inputStream = fileStorageService.downloadFile(objectName)) {
            // EXCEL/CSV单独处理，因为不是Document类型
            if (FileType.EXCEL == FileTypeUtil.getFileType(convertedDocUrl) || FileType.CSV == FileTypeUtil.getFileType(convertedDocUrl)) {
                ExcelSplitter splitter = new ExcelSplitter(documentSplitParam.chunkSize(), false);
                segments = splitter.split(inputStream.readAllBytes(), objectName);
            } else {
                DocumentSplitter splitter = DocumentSplitterFactory.getInstance(documentSplitParam);
                dev.langchain4j.data.document.Document doc = dev.langchain4j.data.document.Document.from(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                segments = splitter.split(doc);
            }
        } catch (Exception e) {
            throw new RuntimeException("下载文档失败: " + e.getMessage(), e);
        }

        // 4. 转换为 KnowledgeSegment 并保存
        for (int i = 0; i < segments.size(); i++) {

            TextSegment segment = segments.get(i);
            Metadata metadata = segment.metadata();
            String chunkId = metadata.getString(MetadataKeyConstant.CHUNK_ID);
            if (chunkId == null || chunkId.isBlank()) {
                chunkId = SnowflakeIdGenerator.getInstance().nextIdStr();
                metadata.put(MetadataKeyConstant.CHUNK_ID, chunkId);
            }

            KnowledgeSegment knowledgeSegment = new KnowledgeSegment();
            knowledgeSegment.setText(segment.text());
            knowledgeSegment.setChunkId(chunkId);
            knowledgeSegment.setMetadata(enrichMetadata(document, versionRecord, metadata));
            knowledgeSegment.setDocumentId(document.getDocId());
            knowledgeSegment.setDocumentVersion(document.getCurrentVersionId());
            knowledgeSegment.setChunkOrder(i);

            // 检查是否需要跳过嵌入
            Integer skipEmbedding = metadata.getInteger(MetadataKeyConstant.SKIP_EMBEDDING);
            if (skipEmbedding != null && skipEmbedding == 1) {
                knowledgeSegment.setSkipEmbedding(1);
                knowledgeSegment.setStatus(SegmentStatus.STORED);
            } else {
                knowledgeSegment.setSkipEmbedding(0);
                knowledgeSegment.setStatus(SegmentStatus.STORED);
            }

            knowledgeSegments.add(knowledgeSegment);
        }

        // 5. 批量保存片段
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean saveResult = segmentService.saveBatch(knowledgeSegments);
        Assert.isTrue(saveResult, "保存知识片段失败");
        log.info("保存知识片段耗时: {}", stopwatch.elapsed().toMillis());

        int segmentCount = knowledgeSegments.size();

        // 6. 更新文档状态为 CHUNKED，并保存分段参数
        boolean advanceResult = documentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CHUNKED);
        Assert.isTrue(advanceResult, "更新文档版本状态失败");

        // 发送文档已分段事件
        publishChunkedEvent(document, segmentCount);

        return segmentCount;
    }

    /**
     * 填充元数据
     *
     * @param document         文档信息
     * @param metadata         元数据
     * @return
     */
    private static String enrichMetadata(Document document, DocumentVersion versionRecord, Metadata metadata) {
        metadata.put(MetadataKeyConstant.DOC_ID, document.getDocId());
        metadata.put(MetadataKeyConstant.FILE_NAME, document.getDocTitle());
        metadata.put(MetadataKeyConstant.URL, versionRecord.getDocUrl());
        if (document.getCurrentVersionId() != null) {
            metadata.put(MetadataKeyConstant.VERSION, document.getCurrentVersionId());
        }
        return JSON.toJSONString(metadata.toMap());
    }

    @Override
    @DistributeLock(scene = "document-embed", keyExpression = "#documentVersion.versionId", waitTime = 0)
    public boolean embedAndStore(DocumentVersion documentVersion) {
        if (documentVersion == null) {
            return false;
        }

        if (documentVersion.getStatus() == DocumentStatus.VECTOR_STORED) {
            log.info("文档版本状态已为VECTOR_STORED，无需重复向量化: {}", documentVersion.getVersionId());
            return true;
        }

        if (documentVersion.getStatus() != DocumentStatus.CHUNKED) {
            log.warn("文档版本状态不是CHUNKED，无法完成向量化: {}", documentVersion.getStatus());
            return false;
        }

        documentService.activateVersion(documentVersion.getVersionId());

        //double check
        long segmentCount = segmentService.count(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", documentVersion.getDocId())
                .eq("document_version", documentVersion.getVersionId())
                .eq("status", SegmentStatus.STORED)
                .eq("skip_embedding", 0));

        if (segmentCount == 0) {
            // 针对非当前版本的文档，取消激活
            List<DocumentVersion> documentVersions = documentVersionService.list(new QueryWrapper<DocumentVersion>()
                    .eq("doc_id", documentVersion.getDocId())
                    .eq("status", DocumentStatus.VECTOR_STORED)
                    .ne("version_id", documentVersion.getVersionId()));

            documentVersions.forEach(version -> documentService.deactivateVersion(version.getVersionId()));
            return true;
        }

        log.warn("向量存储失败，存在部分分段没有存储成功，未成功的数量： " + segmentCount);
        return false;
    }

    @Override
    @DistributeLock(scene = "document-upload", keyExpression = "#docId", waitTime = 0)
    @Transactional(rollbackFor = Exception.class)
    public Document switchVersion(Long docId, Long versionId) {
        // 查询文档
        Document document = documentService.getById(docId);
        Assert.notNull(document, "文档不存在");

        // 查询目标版本
        DocumentVersion versionRecord = documentVersionService.getById(versionId);
        Assert.notNull(versionRecord, "版本不存在");
        Assert.isTrue(versionRecord.getDocId().equals(docId), "版本不属于该文档");

        // 如果已经是当前版本，无需切换
        if (versionId.equals(document.getCurrentVersionId())) {
            return document;
        }

        log.info("切换文档 {} 的版本：从 versionId={} 切换到 versionId={}", docId, document.getCurrentVersionId(), versionId);

        // 更新原版本文档片段状态为 STORED
        LambdaUpdateWrapper<KnowledgeSegment> updateWrapper = Wrappers.<KnowledgeSegment>lambdaUpdate()
                .set(KnowledgeSegment::getStatus, SegmentStatus.STORED)
                .eq(KnowledgeSegment::getDocumentId, document.getDocId())
                .eq(KnowledgeSegment::getDocumentVersion, document.getCurrentVersionId());
        int segAffected = segmentMapper.update(null, updateWrapper);
        log.info("切换版本：旧版本分段状态降级完成, affected={}", segAffected);

        boolean embedResult = embedAndStore(versionRecord);
        Assert.isTrue(embedResult, "更新文档片段状态失败");

        // 更新文档版本
        document.setCurrentVersionId(versionId);
        boolean docUpdateResult = documentService.updateById(document);
        Assert.isTrue(docUpdateResult, "更新文档版本失败");

        return document;
    }

    // ==================== 事件发布方法 ====================

    /**
     * 发送文档已分段事件
     */
    private void publishChunkedEvent(Document document, int segmentCount) {
        log.info("发送文档CHUNKED事件，documentId: {}, segmentCount: {}", document.getDocId(), segmentCount);
        DocumentChunkedEvent event = new DocumentChunkedEvent(this, document.getDocId(), document.getCurrentVersionId(), segmentCount);
        eventPublisher.publishEvent(event);
    }

    // ==================== 辅助方法 ====================

    /**
     * 从MinIO URL中提取对象名称
     */
    private String extractObjectNameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        // URL格式: http://endpoint/bucketName/objectName
        int lastSlashIndex = url.lastIndexOf(bucketName) + bucketName.length();
        if (lastSlashIndex == -1 || lastSlashIndex == url.length() - 1) {
            return null;
        }
        return url.substring(lastSlashIndex + 1);
    }

    /**
     * 创建版本记录
     *
     * @param docId           文档ID
     * @param version         版本号（语义化版本，如 "1.0.0"）
     * @param docUrl          原始文档URL（MinIO）
     * @param convertedDocUrl 转换后的文档URL
     * @param uploadUser      上传用户
     * @param contentHash     内容哈希
     * @param status          文档状态
     * @param changelog       变更说明
     * @return 保存后的版本记录
     */
    private DocumentVersion createVersionRecord(Long docId, String version, String docUrl,
                                                String convertedDocUrl, String uploadUser,
                                                String contentHash, DocumentStatus status, String changelog) {
        DocumentVersion versionRecord = new DocumentVersion();
        versionRecord.setDocId(docId);
        versionRecord.setVersion(version);
        versionRecord.setDocUrl(docUrl);
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        versionRecord.setContentHash(contentHash);
        versionRecord.setStatus(status);
        versionRecord.setUploadUser(uploadUser);
        versionRecord.setChangelog(changelog);
        documentVersionService.save(versionRecord);
        log.info("创建版本记录成功, docId: {}, version: {}, versionId: {}",
                docId, version, versionRecord.getVersionId());
        return versionRecord;
    }

    /**
     * 计算文件内容的SHA-256哈希值
     *
     * @param file 上传的文件
     * @return SHA-256哈希的十六进制字符串
     */
    private String calculateContentHash(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256算法不可用", e);
        }
    }
}
