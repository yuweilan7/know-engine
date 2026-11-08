-- 仅对旧表执行一次。停止旧 Java 进程后运行，保留文档、版本和原分块 ID。
-- 迁移前检查 knowledge_segment 的 chunk_id 非空且唯一，document_version 指向所属文档的版本。
-- 默认对象桶为 know-engine；历史 URL 以 /know-engine/ 之后的部分作为对象键。
ALTER TABLE knowledge_document
  ADD COLUMN active_version_id BIGINT NULL COMMENT '已生效的版本表主键，新索引核验通过后设置',
  ADD COLUMN publish_revision BIGINT NOT NULL DEFAULT 0 COMMENT '发布修订号，只随发布、切换和删除递增',
  ADD COLUMN cleanup_at DATETIME NULL COMMENT '已删除文档最近一次存储清理完成时间';

ALTER TABLE knowledge_document_version
  CHANGE COLUMN status build_status VARCHAR(32) NOT NULL COMMENT '内容阶段：UPLOADING、UPLOADED、CONVERTED、CHUNKED',
  ADD COLUMN source_object_key VARCHAR(2048) NULL COMMENT '原文件对象键',
  ADD COLUMN parsed_object_key VARCHAR(2048) NULL COMMENT '解析完成后的对象键',
  ADD COLUMN index_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '索引状态：NONE、PENDING、READY',
  ADD COLUMN split_param TEXT NULL COMMENT '这一版的切片参数 JSON',
  ADD COLUMN base_publish_revision BIGINT NULL COMMENT '提交构建时的发布修订号，空值表示只建索引',
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '当前阶段连续失败次数',
  ADD COLUMN next_retry_at DATETIME NULL COMMENT '下次自动重试时间',
  ADD COLUMN last_error VARCHAR(512) NULL COMMENT '当前阶段最近的失败原因',
  ADD INDEX idx_version_retry (build_status, index_status, next_retry_at);

ALTER TABLE knowledge_segment
  CHANGE COLUMN document_version version_id BIGINT NOT NULL COMMENT '所属版本表主键',
  MODIFY COLUMN chunk_id VARCHAR(255) NOT NULL COMMENT '稳定的分块标识，也是 ES 文档 ID',
  ADD COLUMN parent_chunk_id VARCHAR(255) NULL COMMENT '同一版本内的父块 ID',
  ADD UNIQUE INDEX uk_chunk_id (chunk_id),
  ADD INDEX idx_document_version_order (document_id, version_id, chunk_order);

UPDATE knowledge_segment
SET parent_chunk_id = CASE WHEN JSON_VALID(metadata)
    THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.parentChunkId')), 'null') END,
    skip_embedding = COALESCE(skip_embedding, 0);

-- 优先恢复原先指向且有分块的就绪版本。原指针若指向构建中的版本，选最近就绪的一版。
CREATE TEMPORARY TABLE ke_active_candidates AS
SELECT v.version_id, ROW_NUMBER() OVER (
  PARTITION BY v.doc_id ORDER BY (v.version_id <=> d.current_version_id) DESC, v.version_id DESC
) AS preference
FROM knowledge_document_version v
JOIN knowledge_document d ON d.doc_id = v.doc_id AND d.deleted = 0
WHERE v.deleted = 0 AND v.build_status = 'VECTOR_STORED'
  AND EXISTS (SELECT 1 FROM knowledge_segment s WHERE s.version_id = v.version_id
              AND s.deleted = 0 AND s.skip_embedding = 0);

UPDATE knowledge_document_version v
JOIN ke_active_candidates c ON c.version_id = v.version_id AND c.preference = 1
SET v.base_publish_revision = 0;
DROP TEMPORARY TABLE ke_active_candidates;

UPDATE knowledge_document_version
SET source_object_key = CASE WHEN LOCATE('/know-engine/', doc_url) > 0
    THEN SUBSTRING(doc_url, LOCATE('/know-engine/', doc_url) + CHAR_LENGTH('/know-engine/')) END,
    parsed_object_key = CASE WHEN LOCATE('/know-engine/', converted_doc_url) > 0
    THEN SUBSTRING(converted_doc_url, LOCATE('/know-engine/', converted_doc_url) + CHAR_LENGTH('/know-engine/')) END;

-- 曾中断解析的版本由用户按“重试”恢复，避免升级时直接重跑长期失败的解析。
UPDATE knowledge_document_version
SET retry_count = 5, last_error = '旧流程的解析未完成，可重试'
WHERE build_status = 'CONVERTING';

-- 用原有分块重建搜索索引，不重新切片，保留评测引用的 chunkId。
UPDATE knowledge_document_version v
SET build_status = 'CHUNKED', index_status = 'PENDING', retry_count = 0, last_error = NULL
WHERE EXISTS (SELECT 1 FROM knowledge_segment s WHERE s.version_id = v.version_id AND s.deleted = 0
              AND s.skip_embedding = 0);

UPDATE knowledge_document_version
SET build_status = CASE
  WHEN parsed_object_key IS NOT NULL THEN 'CONVERTED'
  WHEN source_object_key IS NOT NULL THEN 'UPLOADED'
  ELSE 'UPLOADING'
END
WHERE index_status = 'NONE';

ALTER TABLE knowledge_document
  DROP INDEX idx_status, DROP INDEX idx_status_doc_id,
  DROP COLUMN current_version_id, DROP COLUMN status;
ALTER TABLE knowledge_document_version DROP COLUMN doc_url, DROP COLUMN converted_doc_url;
ALTER TABLE knowledge_segment
  DROP INDEX idx_document_id, DROP INDEX idx_document_id_chunk_order,
  DROP INDEX idx_document_status_skip, DROP INDEX idx_status,
  DROP COLUMN embedding_id, DROP COLUMN status,
  MODIFY COLUMN skip_embedding INT NOT NULL DEFAULT 0 COMMENT '1 为只在 MySQL 保存的父块';
