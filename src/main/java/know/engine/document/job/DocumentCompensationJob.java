package know.engine.document.job;

import know.engine.document.constant.DocumentStatus;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentVersion;
import know.engine.document.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档处理补偿任务
 * 事件驱动的文档处理链路（转换 → 分段 → 向量化）如果中途失败，
 * 由这里的定时任务扫表兜底重试，保障最终一致性。
 */
@Slf4j
@Component
public class DocumentCompensationJob {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentVersionService documentVersionService;

    @Autowired
    private DocumentProcessService documentProcessService;

    @Autowired
    private DocumentCleanupService documentCleanupService;

    /**
     * 向量化补偿任务
     * 扫描 CHUNKED 状态但存在未向量化的 segment，重新触发向量化
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void documentEmbeddingCompensation() {
        log.info("========== 开始执行向量化补偿任务 ==========");
        int successCount = 0;
        int failCount = 0;

        try {
            // 查询 CHUNKED 状态的文档
            LambdaQueryWrapper<DocumentVersion> docQueryWrapper = new LambdaQueryWrapper<>();
            docQueryWrapper.eq(DocumentVersion::getStatus, DocumentStatus.CHUNKED);

            List<DocumentVersion> documents = documentVersionService.list(docQueryWrapper);
            log.info("发现 {} 个 CHUNKED 状态的文档", documents.size());

            for (DocumentVersion documentVersion : documents) {
                Document document = documentService.getById(documentVersion.getDocId());
                if (!document.getCurrentVersionId().equals(documentVersion.getVersionId())) {
                    log.warn("文档 {} 当前版本 {} 不匹配，跳过补偿", documentVersion.getDocId(), documentVersion.getVersion());
                    continue;
                }

                try {
                    // 执行向量化
                    boolean success = documentProcessService.embedAndStore(documentVersion);

                    if (success) {
                        log.info("向量化补偿成功，documentId: {} , version: {}", documentVersion.getDocId(), documentVersion.getVersion());
                        successCount++;
                    } else {
                        log.warn("向量化补偿失败，documentId: {} , version: {}", documentVersion.getDocId(), documentVersion.getVersion());
                        failCount++;
                    }
                } catch (Exception e) {
                    log.error("向量化补偿失败，documentId: {} , version: {}", documentVersion.getDocId(), documentVersion.getVersion(), e);
                    failCount++;
                }
            }
        } catch (Exception e) {
            log.error("向量化补偿任务执行异常", e);
        }

        log.info("========== 向量化补偿任务完成，成功: {}，失败: {} ==========", successCount, failCount);
    }

    /**
     * 扫描所有状态为 VECTOR_STORED 的文档，检查是否存在旧版本残留分段
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void retryFailedCleanups() {
        try {
            List<Document> docsToCleanup = documentService.scanDocumentsNeedingCleanup();
            if (docsToCleanup.isEmpty()) {
                return;
            }

            log.info("定时任务发现 {} 个文档需要清理旧版本数据", docsToCleanup.size());

            for (Document docInfo : docsToCleanup) {
                documentCleanupService.cleanupOldVersionData(docInfo.getDocId(), docInfo.getCurrentVersionId());
            }
        } catch (Exception e) {
            log.error("定时清理任务执行异常: {}", e.getMessage(), e);
        }
    }
}
