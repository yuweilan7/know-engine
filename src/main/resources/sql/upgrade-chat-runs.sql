-- MySQL 8.x. Select the target schema first. Run with the Java instance stopped.
-- These additive DDL statements can be rerun after partial execution.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS chat_run (
  run_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id varchar(64) COLLATE utf8mb4_bin NOT NULL,
  conversation_id varchar(64) NOT NULL,
  client_request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_fingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mode varchar(16) NOT NULL,
  user_message_id varchar(64) NOT NULL,
  assistant_message_id varchar(64) NOT NULL,
  status varchar(16) NOT NULL,
  intended_status varchar(16) NULL,
  finish_token varchar(64) NULL,
  reason_code varchar(64) NULL,
  model_name varchar(128) NULL,
  finish_reason varchar(32) NULL,
  generation_source varchar(16) NULL,
  degraded_stages json NULL,
  context_snapshot json NULL,
  last_stage varchar(32) NULL,
  checkpoint_sequence bigint NOT NULL DEFAULT 0,
  partial tinyint NOT NULL DEFAULT 0,
  title_pending tinyint NOT NULL DEFAULT 0,
  owner_instance_id varchar(64) NULL,
  started_at datetime(3) NULL,
  deadline_at datetime(3) NULL,
  finished_at datetime(3) NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  lock_version int NOT NULL DEFAULT 0,
  deleted tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (run_id),
  UNIQUE KEY uk_chat_run_request (user_id, client_request_id),
  KEY idx_chat_run_conversation (conversation_id, status, deleted),
  KEY idx_chat_run_recovery (status, deleted, updated_at),
  KEY idx_chat_run_ready (status, deleted, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @chat_run_column_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND COLUMN_NAME = 'run_id'),
    'SELECT ''chat_message.run_id already exists''',
    'ALTER TABLE chat_message ADD COLUMN run_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER conversation_id'
);
PREPARE chat_run_migration FROM @chat_run_column_ddl;
EXECUTE chat_run_migration;
DEALLOCATE PREPARE chat_run_migration;

SET @chat_run_index_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND INDEX_NAME = 'uk_chat_message_run_type'),
    'SELECT ''uk_chat_message_run_type already exists''',
    'ALTER TABLE chat_message ADD UNIQUE KEY uk_chat_message_run_type (run_id, type)'
);
PREPARE chat_run_migration FROM @chat_run_index_ddl;
EXECUTE chat_run_migration;
DEALLOCATE PREPARE chat_run_migration;
