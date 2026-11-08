package know.engine.document.service.impl;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import know.engine.document.constant.DocumentStatus;
import know.engine.document.constant.FileType;
import know.engine.document.constant.IndexStatus;
import know.engine.document.entity.*;
import know.engine.document.mapper.DocumentMapper;
import know.engine.document.service.*;
import know.engine.document.util.FileTypeUtil;
import know.engine.document.util.VersionUtil;
import know.engine.infra.snowflake.SnowflakeIdGenerator;
import know.engine.rag.constant.MetadataKeyConstant;
import know.engine.rag.splitter.DocumentSplitterFactory;
import know.engine.rag.splitter.ExcelSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessServiceImpl implements DocumentProcessService {
    private static final int INDEX_BATCH_SIZE = 64;
    public static final int MAX_ATTEMPTS = 5;

    private final DocumentMapper documents;
    private final DocumentVersionService versions;
    private final SegmentService segments;
    private final FileStorageService storage;
    private final FileProcessServiceFactory parsers;
    private final VectorStoreService vectorStore;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    @Override
    public DocumentVersion upload(DocumentUploadParam param, String uploadUser) throws IOException {
        String filename = sourceName(param.file());
        String hash = contentHash(param.file());
        checkVersionNumber(param.version());
        if (param.title() == null || param.title().isBlank()) throw new IllegalArgumentException("请输入文档标题");

        DocumentVersion version = transaction.execute(tx -> {
            Document doc = new Document();
            doc.setDocTitle(param.title());
            doc.setDescription(param.description());
            doc.setPublishRevision(0L);
            documents.insert(doc);
            return createVersion(doc.getDocId(), param.version(), hash, uploadUser, null);
        });
        return saveSource(version, param.file(), filename);
    }

    @Override
    public DocumentVersion uploadNewVersion(Long docId, String number, MultipartFile file, String user, String changelog)
            throws IOException {
        String filename = sourceName(file);
        String hash = contentHash(file);
        checkVersionNumber(number);
        DocumentVersion version = transaction.execute(tx -> {
            lockDocument(docId);
            String latest = versions.getLatestVersion(docId);
            if (latest != null && VersionUtil.compareVersions(number, latest) <= 0) {
                throw new IllegalArgumentException("新版本号必须大于 " + latest);
            }
            return createVersion(docId, number, hash, user, changelog);
        });
        return saveSource(version, file, filename);
    }

    private DocumentVersion createVersion(long docId, String number, String hash, String user, String changelog) {
        DocumentVersion version = new DocumentVersion();
        version.setDocId(docId);
        version.setVersion(number);
        version.setContentHash(hash);
        version.setUploadUser(user);
        version.setChangelog(changelog);
        version.setBuildStatus(DocumentStatus.UPLOADING);
        version.setIndexStatus(IndexStatus.NONE);
        version.setRetryCount(0);
        versions.save(version);
        return version;
    }

    private DocumentVersion saveSource(DocumentVersion version, MultipartFile file, String filename) throws IOException {
        String key = prefix(version) + "source/" + filename;
        try {
            storage.uploadFile(file, key);
        } catch (Exception e) {
            recordFailure(version, "上传原文件失败，请重新上传", false);
            throw new IOException("上传原文件失败，请重新上传", e);
        }
        transaction.executeWithoutResult(tx -> {
            // 上传请求和删除可能相遇。文档已删除时，后台清理会回收这次上传。
            lockDocument(version.getDocId());
            version.setSourceObjectKey(key);
            version.setBuildStatus(DocumentStatus.UPLOADED);
            versions.updateById(version);
        });
        return requireVersion(version.getVersionId());
    }

    @Override
    public void requestBuild(long versionId, DocumentSplitParam params) {
        DocumentSplitParam normalized = normalize(params);
        String json;
        try {
            json = objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存切片参数", e);
        }
        DocumentVersion requested = requireVersion(versionId);
        transaction.executeWithoutResult(tx -> {
            Document doc = lockDocument(requested.getDocId());
            DocumentVersion version = requireVersion(versionId);
            if (version.getBuildStatus() == DocumentStatus.UPLOADING) {
                throw new IllegalArgumentException("原文件尚未上传成功");
            }
            if (version.getSplitParam() != null) {
                if (version.getSplitParam().equals(json)) return;
                throw new IllegalArgumentException("已提交切片参数，修改参数请上传新版本");
            }
            if (version.getBuildStatus() == DocumentStatus.CHUNKED) {
                throw new IllegalArgumentException("该版本已经切片");
            }
            // 此处只登记请求。解析、切片、写 ES 全由同一个后台入口执行。
            versions.lambdaUpdate().eq(DocumentVersion::getVersionId, versionId)
                    .set(DocumentVersion::getSplitParam, json)
                    .set(DocumentVersion::getBasePublishRevision, doc.getPublishRevision()).update();
        });
    }

    @Override
    public void retry(long versionId) {
        DocumentVersion requested = requireVersion(versionId);
        transaction.executeWithoutResult(tx -> {
            lockDocument(requested.getDocId());
            DocumentVersion version = requireVersion(versionId);
            if (version.getBuildStatus() == DocumentStatus.UPLOADING) {
                throw new IllegalArgumentException("原文件未上传成功，请重新上传");
            }
            // 保留原发布修订号，重试无法覆盖用户后续的手动发布。
            clearFailure(versionId);
        });
    }

    @Override
    public Document switchVersion(Long docId, Long versionId) {
        return transaction.execute(tx -> {
            Document doc = lockDocument(docId);
            DocumentVersion target = requireVersion(versionId);
            if (!docId.equals(target.getDocId()) || target.getIndexStatus() != IndexStatus.READY) {
                throw new IllegalArgumentException("请先选择本文档已经完成索引的版本");
            }
            publish(doc, versionId);
            return documents.selectById(docId);
        });
    }

    /** 定时任务逐条调用。阶段成功后再落库，下次执行从上一个已完成的阶段继续。 */
    @Override
    public void advanceVersion(long versionId) {
        DocumentVersion version = versions.getById(versionId);
        if (version == null || documents.selectById(version.getDocId()) == null) return;
        try {
            if (version.getBuildStatus() == DocumentStatus.UPLOADED) {
                parse(version);
            } else if (version.getBuildStatus() == DocumentStatus.CONVERTED && version.getSplitParam() != null) {
                split(version);
            } else if (version.getBuildStatus() == DocumentStatus.CHUNKED && version.getIndexStatus() == IndexStatus.PENDING) {
                indexAndPublish(version);
            }
        } catch (Exception e) {
            log.warn("文档构建失败 docId={}, versionId={}, stage={}", version.getDocId(), versionId,
                    version.getBuildStatus(), e);
            String stage = switch (version.getBuildStatus()) {
                case UPLOADING -> "上传";
                case UPLOADED -> "解析";
                case CONVERTED -> "切片";
                case CHUNKED -> "索引构建";
            };
            recordFailure(version, stage + "失败，请检查服务日志后重试", true);
        }
    }

    private void parse(DocumentVersion version) throws Exception {
        String source = version.getSourceObjectKey();
        String filename = source.substring(source.lastIndexOf('/') + 1);
        FileProcessService parser = parsers.get(FileTypeUtil.getFileType(filename));
        String parsedKey = source;
        if (parser != null) {
            // 每次尝试使用独立输出名，成功后只登记这一份结果。
            String output = prefix(version) + "parsed/" + UUID.randomUUID() + "/";
            try (InputStream input = storage.downloadFile(source)) {
                parsedKey = parser.processDocument(input, filename, output);
            }
        }
        String completedKey = parsedKey;
        transaction.executeWithoutResult(tx -> {
            lockDocument(version.getDocId());
            versions.lambdaUpdate().eq(DocumentVersion::getVersionId, version.getVersionId())
                    .eq(DocumentVersion::getBuildStatus, DocumentStatus.UPLOADED)
                    .set(DocumentVersion::getParsedObjectKey, completedKey)
                    .set(DocumentVersion::getBuildStatus, DocumentStatus.CONVERTED).update();
            clearFailure(version.getVersionId());
        });
    }

    private void split(DocumentVersion version) throws Exception {
        DocumentSplitParam params = objectMapper.readValue(version.getSplitParam(), DocumentSplitParam.class);
        String key = version.getParsedObjectKey();
        FileType type = FileTypeUtil.getFileType(key);
        List<TextSegment> textSegments;
        try (InputStream input = storage.downloadFile(key)) {
            byte[] bytes = input.readAllBytes();
            if (type == FileType.EXCEL || type == FileType.CSV) {
                textSegments = new ExcelSplitter(params.chunkSize(), false).split(bytes, key);
            } else {
                textSegments = DocumentSplitterFactory.getInstance(params).split(
                        dev.langchain4j.data.document.Document.from(new String(bytes, StandardCharsets.UTF_8)));
            }
        }
        List<KnowledgeSegment> chunks = new ArrayList<>();
        for (TextSegment text : textSegments) {
            if (text.text().isBlank()) continue;
            var metadata = text.metadata();
            String chunkId = metadata.getString(MetadataKeyConstant.CHUNK_ID);
            KnowledgeSegment chunk = new KnowledgeSegment();
            chunk.setChunkId(chunkId == null || chunkId.isBlank() ? SnowflakeIdGenerator.getInstance().nextIdStr() : chunkId);
            chunk.setParentChunkId(metadata.getString(MetadataKeyConstant.PARENT_CHUNK_ID));
            chunk.setDocumentId(version.getDocId());
            chunk.setVersionId(version.getVersionId());
            chunk.setText(text.text());
            chunk.setChunkOrder(chunks.size());
            chunk.setMetadata(JSON.toJSONString(metadata.toMap()));
            chunk.setSkipEmbedding(Objects.equals(metadata.getInteger(MetadataKeyConstant.SKIP_EMBEDDING), 1) ? 1 : 0);
            chunks.add(chunk);
        }
        if (chunks.stream().noneMatch(chunk -> chunk.getSkipEmbedding() == 0)) {
            throw new IllegalArgumentException("文件没有可检索的文本");
        }
        // 分块和阶段一起提交。重试索引时直接复用这批块及其 ID。
        transaction.executeWithoutResult(tx -> {
            lockDocument(version.getDocId());
            if (requireVersion(version.getVersionId()).getBuildStatus() != DocumentStatus.CONVERTED) return;
            segments.saveBatch(chunks);
            versions.lambdaUpdate().eq(DocumentVersion::getVersionId, version.getVersionId())
                    .set(DocumentVersion::getBuildStatus, DocumentStatus.CHUNKED)
                    .set(DocumentVersion::getIndexStatus, IndexStatus.PENDING).update();
            clearFailure(version.getVersionId());
        });
    }

    private void indexAndPublish(DocumentVersion version) {
        Document doc = documents.selectById(version.getDocId());
        if (doc == null) return;
        List<KnowledgeSegment> chunks = segments.lambdaQuery()
                .eq(KnowledgeSegment::getDocumentId, version.getDocId())
                .eq(KnowledgeSegment::getVersionId, version.getVersionId())
                .eq(KnowledgeSegment::getSkipEmbedding, 0)
                .orderByAsc(KnowledgeSegment::getChunkOrder).list();
        if (chunks.isEmpty()) throw new IllegalStateException("没有可检索分块");
        for (int start = 0; start < chunks.size(); start += INDEX_BATCH_SIZE) {
            if (documents.selectById(version.getDocId()) == null) return;
            vectorStore.embedAndStore(chunks.subList(start, Math.min(start + INDEX_BATCH_SIZE, chunks.size())),
                    doc.getDocTitle(), storage.objectUrl(version.getSourceObjectKey()));
        }
        vectorStore.verifySearchable(version.getDocId(), version.getVersionId(), chunks.size());

        transaction.executeWithoutResult(tx -> {
            Document latest = lockDocument(version.getDocId());
            versions.lambdaUpdate().eq(DocumentVersion::getVersionId, version.getVersionId())
                    .set(DocumentVersion::getIndexStatus, IndexStatus.READY).update();
            clearFailure(version.getVersionId());
            if (Objects.equals(latest.getPublishRevision(), version.getBasePublishRevision())) {
                publish(latest, version.getVersionId());
            }
        });
    }

    private void publish(Document doc, long versionId) {
        // 调用方已经锁定文档行。更新指针时推进发布修订号，防止旧请求覆盖手动切换。
        documents.publish(doc.getDocId(), versionId);
    }

    private void recordFailure(DocumentVersion version, String message, boolean retry) {
        int attempts = version.getRetryCount() + 1;
        LocalDateTime next = retry && attempts < MAX_ATTEMPTS
                ? LocalDateTime.now().plusSeconds(15L << (attempts - 1)) : null;
        versions.lambdaUpdate().eq(DocumentVersion::getVersionId, version.getVersionId())
                .set(DocumentVersion::getLastError, message)
                .set(DocumentVersion::getRetryCount, retry ? attempts : MAX_ATTEMPTS)
                .set(DocumentVersion::getNextRetryAt, next).update();
    }

    private void clearFailure(long versionId) {
        versions.lambdaUpdate().eq(DocumentVersion::getVersionId, versionId)
                .set(DocumentVersion::getLastError, null)
                .set(DocumentVersion::getRetryCount, 0)
                .set(DocumentVersion::getNextRetryAt, null).update();
    }

    private Document lockDocument(long docId) {
        Document doc = documents.lockLive(docId);
        if (doc == null) throw new IllegalArgumentException("文档不存在或已删除");
        return doc;
    }

    private DocumentVersion requireVersion(long id) {
        DocumentVersion version = versions.getById(id);
        if (version == null) throw new IllegalArgumentException("版本不存在");
        return version;
    }

    private static String prefix(DocumentVersion version) {
        return "documents/" + version.getDocId() + "/versions/" + version.getVersionId() + "/";
    }

    private static String sourceName(MultipartFile file) {
        String name = Objects.toString(file.getOriginalFilename(), "").replace((char) 92, '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        FileTypeUtil.getFileType(name);
        if (file.isEmpty()) throw new IllegalArgumentException("请选择非空文件");
        return name;
    }

    private static void checkVersionNumber(String number) {
        if (number == null || !number.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")) {
            throw new IllegalArgumentException("版本号格式应为 1.0.0");
        }
    }

    private static String contentHash(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentSplitParam normalize(DocumentSplitParam p) {
        if (p.chunkSize() == null || p.chunkSize() <= 0 || p.chunkSize() > 100000) {
            throw new IllegalArgumentException("切片大小须在 1 到 100000 之间");
        }
        int overlap = p.overlap() == null ? 0 : p.overlap();
        if (overlap < 0 || overlap >= p.chunkSize()) throw new IllegalArgumentException("重叠长度须小于切片大小");
        var normalized = new DocumentSplitParam(p.splitType(), p.chunkSize(), overlap,
                p.titleLevel() == null ? 3 : p.titleLevel(), p.separator(), p.regex());
        if (DocumentSplitterFactory.getInstance(normalized) == null) throw new IllegalArgumentException("未知切片方式");
        return normalized;
    }
}
