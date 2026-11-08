-- Know Engine 初始表结构。此文件不包含业务数据。
SET NAMES utf8mb4;

CREATE TABLE `chat_conversation`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `conversation_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话唯一标识',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '会话标题',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_conversation_id`(`conversation_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '会话表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `chat_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `message_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息唯一标识',
  `conversation_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属会话ID',
  `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色：USER/ASSISTANT',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '消息内容',
  `transform_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '改写后的内容',
  `token_count` int NULL DEFAULT NULL COMMENT 'Token数量',
  `model_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '使用的模型名称',
  `rag_references` json NULL COMMENT 'RAG引用内容JSON数组，包含document_id、document_title、chunk_id、chunk_content、similarity_score、retrieval_source等字段',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_message_id`(`message_id` ASC) USING BTREE,
  INDEX `idx_conversation_id`(`conversation_id` ASC) USING BTREE,
  INDEX `idx_create_time`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '消息表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `eval_run`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `run_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评测批次唯一标识',
  `dataset_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '测试集路径',
  `config_json` json NULL COMMENT '评测配置快照',
  `summary_json` json NULL COMMENT '汇总指标快照（不含单条 case 明细）',
  `total_cases` int NOT NULL DEFAULT 0 COMMENT '总用例数',
  `evaluated_cases` int NOT NULL DEFAULT 0 COMMENT '含检索 GT 且成功评分的用例数',
  `generation_evaluated_cases` int NOT NULL DEFAULT 0 COMMENT '成功 LLM Judge 的用例数',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'COMPLETED' COMMENT '状态：COMPLETED/FAILED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_run_id`(`run_id` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'RAG评测批次表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `eval_run_case`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `run_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属评测批次ID',
  `case_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '测试用例ID',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户问题',
  `answer` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '模型回答',
  `transform_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '查询改写结果',
  `retrieved_chunk_ids` json NULL COMMENT '召回 chunk ID 列表',
  `retrieved_document_ids` json NULL COMMENT '召回 document ID 列表',
  `rag_references` json NULL COMMENT 'RAG 引用详情',
  `retrieval_scores` json NULL COMMENT '检索层评分',
  `generation_scores` json NULL COMMENT '生成层评分',
  `latency_ms` bigint NOT NULL DEFAULT 0 COMMENT '推理耗时（毫秒）',
  `success` tinyint NOT NULL DEFAULT 1 COMMENT '是否成功：1-成功，0-失败',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_run_id`(`run_id` ASC) USING BTREE,
  INDEX `idx_run_case`(`run_id` ASC, `case_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'RAG评测单条用例结果表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `knowledge_document`  (
  `doc_id` bigint NOT NULL AUTO_INCREMENT COMMENT '文档ID',
  `doc_title` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文档标题',
  `expire_date` date NULL DEFAULT NULL COMMENT '文档失效日期',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '文档描述',
  `extension` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '扩展字段，保存JSON字符串',
  `active_version_id` bigint NULL DEFAULT NULL COMMENT '已生效的版本表主键，新索引核验通过后设置',
  `publish_revision` bigint NOT NULL DEFAULT 0 COMMENT '发布修订号，只随发布、切换和删除递增',
  `cleanup_at` datetime NULL COMMENT '已删除文档最近一次存储清理完成时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`doc_id`) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识文档表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `knowledge_document_version`  (
  `version_id` bigint NOT NULL AUTO_INCREMENT COMMENT '版本ID',
  `doc_id` bigint NOT NULL COMMENT '关联文档ID（knowledge_document.doc_id）',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '1.0.0' COMMENT '版本号（语义化版本，如 1.0.0）',
  `source_object_key` varchar(2048) NULL COMMENT '原文件对象键',
  `parsed_object_key` varchar(2048) NULL COMMENT '解析完成后的对象键',
  `content_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '该版本文档内容哈希值（SHA-256）',
  `build_status` varchar(32) NOT NULL COMMENT '内容阶段：UPLOADING、UPLOADED、CONVERTED、CHUNKED',
  `index_status` varchar(16) NOT NULL DEFAULT 'NONE' COMMENT '索引状态：NONE、PENDING、READY',
  `split_param` text NULL COMMENT '这一版的切片参数 JSON',
  `base_publish_revision` bigint NULL COMMENT '提交构建时的发布修订号，空值表示只建索引',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '当前阶段连续失败次数',
  `next_retry_at` datetime NULL COMMENT '下次自动重试时间',
  `last_error` varchar(512) NULL COMMENT '当前阶段最近的失败原因',
  `upload_user` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '该版本上传用户',
  `changelog` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '版本变更说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`version_id`) USING BTREE,
  UNIQUE INDEX `uk_doc_version`(`doc_id` ASC, `version` ASC) USING BTREE,
  INDEX `idx_doc_id`(`doc_id` ASC) USING BTREE,
  INDEX `idx_version_retry` (`build_status`, `index_status`, `next_retry_at`),
  INDEX `idx_content_hash`(`content_hash` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文档版本表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `knowledge_segment`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '片段ID',
  `text` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文本内容',
  `chunk_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '稳定的分块标识，也是 ES 文档 ID',
  `metadata` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '元数据',
  `document_id` bigint NOT NULL COMMENT '所属文档ID',
  `version_id` bigint NOT NULL COMMENT '所属版本表主键',
  `parent_chunk_id` varchar(255) NULL COMMENT '同一版本内的父块 ID',
  `chunk_order` int NOT NULL COMMENT '顺序',
  `skip_embedding` int NOT NULL DEFAULT 0 COMMENT '1 为只在 MySQL 保存的父块',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_chunk_id` (`chunk_id`),
  INDEX `idx_document_version_order` (`document_id`, `version_id`, `chunk_order`),
  INDEX `idx_document_version` (`version_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识片段表' ROW_FORMAT = DYNAMIC;
