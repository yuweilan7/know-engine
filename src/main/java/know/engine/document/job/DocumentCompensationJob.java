package know.engine.document.job;

import know.engine.document.constant.DocumentStatus;
import know.engine.document.constant.IndexStatus;
import know.engine.document.entity.DocumentVersion;
import know.engine.document.mapper.DocumentMapper;
import know.engine.document.mapper.DocumentVersionMapper;
import know.engine.document.mapper.SegmentMapper;
import know.engine.document.service.DocumentProcessService;
import know.engine.document.service.DocumentVersionService;
import know.engine.document.service.FileStorageService;
import know.engine.document.service.VectorStoreService;
import know.engine.document.service.impl.DocumentProcessServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentCompensationJob {
    private final DocumentMapper documents;
    private final DocumentVersionMapper versionMapper;
    private final SegmentMapper segments;
    private final DocumentVersionService versions;
    private final DocumentProcessService process;
    private final VectorStoreService vectorStore;
    private final FileStorageService storage;
    private final TransactionTemplate transaction;

    /**
     * 唯一构建执行入口。请求只在版本表登记意图。
     * fixedDelay 等本次扫描完成再开始下一次，进程重启后从已保存阶段恢复。
     */
    @Scheduled(fixedDelayString = "${know-engine.document.poll-ms:3000}")
    public void processPendingVersions() {
        versions.lambdaQuery()
                .apply("EXISTS (SELECT 1 FROM knowledge_document d WHERE d.doc_id = knowledge_document_version.doc_id AND d.deleted = 0)")
                .lt(DocumentVersion::getRetryCount, DocumentProcessServiceImpl.MAX_ATTEMPTS)
                .and(q -> q.isNull(DocumentVersion::getNextRetryAt)
                        .or().le(DocumentVersion::getNextRetryAt, LocalDateTime.now()))
                .and(q -> q.eq(DocumentVersion::getBuildStatus, DocumentStatus.UPLOADED)
                        .or(b -> b.eq(DocumentVersion::getBuildStatus, DocumentStatus.CONVERTED)
                                .isNotNull(DocumentVersion::getSplitParam))
                        .or(b -> b.eq(DocumentVersion::getBuildStatus, DocumentStatus.CHUNKED)
                                .eq(DocumentVersion::getIndexStatus, IndexStatus.PENDING)))
                .orderByAsc(DocumentVersion::getVersionId).last("LIMIT 8")
                .list().forEach(v -> process.advanceVersion(v.getVersionId()));
    }

    /** 只清理已删除文档。未生效的正常版本仍可继续构建或回滚。 */
    @Scheduled(fixedDelay = 30000)
    public void cleanupDeletedDocuments() {
        for (Long docId : documents.pendingCleanupIds()) {
            try {
                vectorStore.removeByDocId(docId);
                storage.deletePrefix("documents/" + docId + "/");
                transaction.executeWithoutResult(tx -> {
                    segments.physicalDeleteByDocumentId(docId);
                    versionMapper.physicalDeleteByDocId(docId);
                    documents.markCleaned(docId);
                });
            } catch (Exception e) {
                // 保留删除记录与待清理标记，下一次继续。
                log.warn("删除文档的存储清理失败，稍后重试 docId={}", docId, e);
            }
        }
    }
}
