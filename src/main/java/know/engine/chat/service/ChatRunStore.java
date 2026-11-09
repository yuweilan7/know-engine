package know.engine.chat.service;

import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.entity.ChatMessage;
import know.engine.rag.RagContext.Prepared;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** chat_run 是状态的唯一来源。所有与一轮消息相关的更新和状态变更共享同一 JDBC 事务。 */
@Repository
class ChatRunStore {
    private static final String RUN_COLUMNS = """
            SELECT run_id, user_id, conversation_id, user_message_id, assistant_message_id, mode, status,
                request_fingerprint, reason_code, finish_token, checkpoint_sequence, partial, deleted, created_at,
                model_name, finish_reason, generation_source, degraded_stages FROM chat_run
            """;
    private static final String UPDATE_MESSAGES = """
            UPDATE chat_run r
            STRAIGHT_JOIN chat_message u ON u.message_id = r.user_message_id AND u.run_id = r.run_id AND u.deleted = 0
            STRAIGHT_JOIN chat_message a ON a.message_id = r.assistant_message_id AND a.run_id = r.run_id AND a.deleted = 0
            """;
    enum Status {
        READY, RUNNING, FINALIZING, COMPLETED, CANCELLED, FAILED, TIMED_OUT;
        boolean terminal() { return this != READY && this != RUNNING && this != FINALIZING; }
    }

    record Command(String userId, String clientRequestId, String conversationId, String question, ChatService.Mode mode,
                   String fingerprint) {}
    record Run(String id, String userId, String conversationId, String userMessageId, String assistantMessageId,
               ChatService.Mode mode, Status status, String fingerprint, String reason, String finishToken,
               long checkpointSequence, boolean partial, boolean deleted, LocalDateTime createdAt,
               String modelName, String finishReason, String generationSource, String degradedJson) {}
    record Created(Run run, boolean newRun) {}
    record Snapshot(String answer, Prepared context, long sequence, String lastStage, List<String> degraded,
                    String modelName, String finishReason, String generationSource, Integer tokenCount) {}
    record Outcome(Status status, ChatFailure.Reason reason, String token, Snapshot snapshot) {}

    private final JdbcTemplate jdbc;
    private final JdbcTemplate finalizationJdbc;
    private final DruidDataSource finalizationPool;
    private final ChatConversationService conversations;
    private final ChatMessageService messages;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    ChatRunStore(JdbcTemplate jdbc, ChatConversationService conversations, ChatMessageService messages, ObjectMapper json,
                 PlatformTransactionManager manager, ChatSettings settings) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.messages = messages;
        this.json = json;
        if (jdbc.getDataSource() instanceof DruidDataSource druid) {
            // 预留独立连接，常规历史、检索或页面查询占满主池时仍可保存终态。
            finalizationPool = druid.cloneDruidDataSource();
            finalizationPool.setName("chat-finalization");
            finalizationPool.setInitialSize(1);
            finalizationPool.setMinIdle(1);
            finalizationPool.setMaxActive(Math.min(4, settings.getMaxConcurrent()));
            finalizationPool.setMaxWait(Math.min(1000, settings.getFinalizeTimeout().toMillis()));
            finalizationJdbc = new JdbcTemplate(finalizationPool);
            finalizationJdbc.setQueryTimeout((int) Math.max(1, settings.getFinalizeTimeout().toSeconds()));
        } else {
            finalizationPool = null;
            finalizationJdbc = jdbc;
        }
        this.tx = new TransactionTemplate(manager);
        tx.setTimeout((int) Math.max(1, settings.getFinalizeTimeout().toSeconds()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @PostConstruct
    void warmFinalizationPool() throws SQLException {
        if (finalizationPool != null) finalizationPool.init();
    }

    @PreDestroy
    void closeFinalizationPool() {
        if (finalizationPool != null) finalizationPool.close();
    }

    Run lookup(Command command) {
        Run found = one(RUN_COLUMNS + " WHERE user_id = ? AND client_request_id = ?",
                command.userId(), command.clientRequestId());
        if (found == null) return null;
        if (found.deleted()) throw new ResponseStatusException(HttpStatus.GONE, "会话已删除");
        if (!found.fingerprint().equals(command.fingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clientRequestId 已用于不同的请求");
        }
        return found;
    }

    Created create(Command command) {
        try {
            return tx.execute(status -> {
                Run existing = lookup(command);
                if (existing != null) return new Created(existing, false);
                String conversationId = command.conversationId();
                if (conversationId == null) {
                    int end = command.question().offsetByCodePoints(0,
                            Math.min(20, command.question().codePointCount(0, command.question().length())));
                    conversationId = conversations.createConversation(command.userId(), command.question().substring(0, end));
                } else {
                    // 会话行锁将同一会话的创建和删除串行化。拿到锁后重新检查幂等键和活动轮次。
                    requireConversation(command.userId(), conversationId, true);
                    existing = lookup(command);
                    if (existing != null) return new Created(existing, false);
                    requireInactive(conversationId);
                }
                String id = id();
                String userMessageId = id();
                String assistantMessageId = id();
                jdbc.update("""
                        INSERT INTO chat_run (run_id, user_id, conversation_id, client_request_id, request_fingerprint,
                            mode, user_message_id, assistant_message_id, status, title_pending)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?)
                        """, id, command.userId(), conversationId, command.clientRequestId(), command.fingerprint(),
                        command.mode().name(), userMessageId, assistantMessageId, command.conversationId() == null);
                insertMessage(conversationId, id, userMessageId, ChatMessageType.USER, command.question());
                insertMessage(conversationId, id, assistantMessageId, ChatMessageType.ASSISTANT, null);
                return new Created(find(id), true);
            });
        } catch (DuplicateKeyException error) {
            // 并发首次请求可能各自创建了会话。失败事务整笔回滚，再读取唯一键的获胜记录。
            Run existing = lookup(command);
            if (existing != null) return new Created(existing, false);
            throw error;
        }
    }

    private void insertMessage(String conversationId, String runId, String messageId, ChatMessageType type, String content) {
        var message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setMessageId(messageId);
        message.setRunId(runId);
        message.setContent(content);
        message.setType(type);
        if (!messages.save(message)) throw new IllegalStateException("Could not insert chat message");
    }

    Run owned(String id, String userId) {
        Run run = find(id);
        if (run == null || !run.userId().equals(userId)) throw missing();
        if (run.deleted()) throw new ResponseStatusException(HttpStatus.GONE, "会话已删除");
        return run;
    }

    Run find(String id) { return one(RUN_COLUMNS + " WHERE run_id = ?", id); }

    boolean start(Run run, String owner, Duration readyTtl, Duration total) {
        return jdbc.update("""
                UPDATE chat_run SET status = 'RUNNING', owner_instance_id = ?, started_at = CURRENT_TIMESTAMP(3),
                    deadline_at = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3)), lock_version = lock_version + 1
                WHERE run_id = ? AND status = 'READY' AND deleted = 0
                    AND created_at > TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3))
                """, owner, total.toMillis() * 1000, run.id(), -readyTtl.toMillis() * 1000) == 1;
    }

    boolean takeTitle(Run run) {
        return jdbc.update("UPDATE chat_run SET title_pending = 0 WHERE run_id = ? AND title_pending = 1 AND deleted = 0",
                run.id()) == 1;
    }

    boolean cancelReady(Run run, ChatFailure.Reason reason) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int changed = jdbc.update("""
                    UPDATE chat_run SET status = 'CANCELLED', intended_status = 'CANCELLED', finish_token = ?,
                        reason_code = ?, finished_at = CURRENT_TIMESTAMP(3), lock_version = lock_version + 1
                    WHERE run_id = ? AND status = 'READY' AND deleted = 0
                    """, id(), reason.name(), run.id());
            if (changed == 1) touch(run.conversationId());
            return changed == 1;
        }));
    }

    void checkpoint(Run run, Snapshot snapshot) {
        // 单条 InnoDB 更新同时写轮次和成对消息，减少远程 MySQL 往返。序号与状态条件防止晚到的快照覆盖终态。
        Prepared context = snapshot.context();
        jdbc.update(UPDATE_MESSAGES + """
                SET r.context_snapshot = ?, r.checkpoint_sequence = ?, r.degraded_stages = ?, r.last_stage = ?,
                    r.partial = ?, r.lock_version = r.lock_version + 1,
                    u.transform_content = ?, a.content = ?, a.rag_references = ?
                WHERE r.run_id = ? AND r.status = 'RUNNING' AND r.deleted = 0 AND r.checkpoint_sequence < ?
                """, encode(context), snapshot.sequence(), encode(snapshot.degraded()), snapshot.lastStage(),
                !snapshot.answer().isEmpty(), context == null ? null : context.query(), snapshot.answer(),
                context == null ? null : encode(context.references()), run.id(), snapshot.sequence());
    }

    Run finish(Run run, Outcome outcome) {
        var snapshot = outcome.snapshot();
        // 先将收尾意图持久化。重试使用相同 token，可读回已提交结果或接着完成同一份快照。
        finalizationJdbc.update("""
                UPDATE chat_run SET status = 'FINALIZING', intended_status = ?, finish_token = ?,
                    reason_code = ?, lock_version = lock_version + 1
                WHERE run_id = ? AND status = 'RUNNING' AND deleted = 0
                """, outcome.status().name(), outcome.token(), outcome.reason().name(), run.id());
        // FINALIZING 的 token 条件保证同一事务只生效一次。更新结束后读回确认，覆盖驱动报告超时但已提交的情况。
        Prepared context = snapshot.context();
        finalizationJdbc.update(UPDATE_MESSAGES + """
                STRAIGHT_JOIN chat_conversation c ON c.conversation_id = r.conversation_id AND c.deleted = 0
                SET r.status = ?, r.context_snapshot = ?, r.checkpoint_sequence = ?, r.partial = ?,
                    r.degraded_stages = ?, r.last_stage = ?, r.model_name = ?, r.finish_reason = ?, r.generation_source = ?,
                    r.finished_at = CURRENT_TIMESTAMP(3), r.lock_version = r.lock_version + 1,
                    u.transform_content = ?, a.content = ?, a.rag_references = ?, a.model_name = ?, a.token_count = ?,
                    c.updated_at = ?
                WHERE r.run_id = ? AND r.finish_token = ? AND r.status = 'FINALIZING' AND r.deleted = 0
                """, outcome.status().name(), encode(context), snapshot.sequence(),
                outcome.status() != Status.COMPLETED && !snapshot.answer().isEmpty(), encode(snapshot.degraded()),
                snapshot.lastStage(), snapshot.modelName(), snapshot.finishReason(), snapshot.generationSource(),
                context == null ? null : context.query(), snapshot.answer(), context == null ? null : encode(context.references()),
                snapshot.modelName(), snapshot.tokenCount(), LocalDateTime.now(), run.id(), outcome.token());
        Run saved = finalizationJdbc.query(RUN_COLUMNS + " WHERE run_id = ?", ChatRunStore::readRun, run.id())
                .stream().findFirst().orElse(null);
        if (saved == null || saved.status() != outcome.status() || !Objects.equals(saved.finishToken(), outcome.token())) {
            throw new IllegalStateException("Run finish could not be confirmed");
        }
        return saved;
    }

    void requireConversation(String userId, String conversationId, boolean lock) {
        var owners = jdbc.queryForList("SELECT user_id FROM chat_conversation WHERE conversation_id = ? AND deleted = 0"
                + (lock ? " FOR UPDATE" : ""), String.class, conversationId);
        if (owners.isEmpty() || !owners.getFirst().equals(userId)) throw missing();
    }

    void deleteConversation(String userId, String conversationId) {
        tx.executeWithoutResult(status -> {
            requireConversation(userId, conversationId, true);
            requireInactive(conversationId);
            jdbc.update("UPDATE chat_run SET deleted = 1 WHERE conversation_id = ? AND deleted = 0", conversationId);
            messages.deleteMessagesByConversationId(conversationId);
            conversations.deleteConversation(conversationId);
        });
    }

    List<Run> forConversation(String conversationId) {
        return jdbc.query(RUN_COLUMNS + " WHERE conversation_id = ? AND deleted = 0",
                ChatRunStore::readRun, conversationId);
    }

    private void requireInactive(String conversationId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_run WHERE conversation_id = ? AND deleted = 0
                    AND status IN ('READY', 'RUNNING', 'FINALIZING')
                """, Integer.class, conversationId);
        if (count != null && count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "会话有活动回答，请先停止");
    }

    private void touch(String conversationId) {
        jdbc.update("UPDATE chat_conversation SET updated_at = ? WHERE conversation_id = ? AND deleted = 0",
                LocalDateTime.now(), conversationId);
    }

    List<Run> expiredReady(Duration ttl) {
        return jdbc.query(RUN_COLUMNS + """
                WHERE status = 'READY' AND deleted = 0
                    AND created_at <= TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3))
                """, ChatRunStore::readRun, -ttl.toMillis() * 1000);
    }

    int readyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_run WHERE status = 'READY' AND deleted = 0", Integer.class);
    }

    List<Run> interruptedRuns() {
        return jdbc.query(RUN_COLUMNS + " WHERE status IN ('RUNNING', 'FINALIZING') AND deleted = 0",
                ChatRunStore::readRun);
    }

    boolean recover(Run run) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var current = lock(run.id());
            if (current == null || current.status().terminal()) return true;
            if (current.status() != Status.RUNNING && current.status() != Status.FINALIZING) return false;
            jdbc.update("""
                    UPDATE chat_run SET status = 'FAILED', intended_status = 'FAILED', reason_code = 'RECOVERY_INCOMPLETE',
                        finished_at = CURRENT_TIMESTAMP(3), lock_version = lock_version + 1 WHERE run_id = ?
                    """, run.id());
            touch(run.conversationId());
            return true;
        }));
    }

    private Run lock(String id) { return one(RUN_COLUMNS + " WHERE run_id = ? FOR UPDATE", id); }

    private Run one(String query, Object... params) {
        return jdbc.query(query, ChatRunStore::readRun, params).stream().findFirst().orElse(null);
    }

    private static Run readRun(ResultSet row, int ignored) throws SQLException {
        return new Run(row.getString("run_id"), row.getString("user_id"), row.getString("conversation_id"),
                row.getString("user_message_id"), row.getString("assistant_message_id"),
                ChatService.Mode.valueOf(row.getString("mode")), Status.valueOf(row.getString("status")),
                row.getString("request_fingerprint"), row.getString("reason_code"), row.getString("finish_token"),
                row.getLong("checkpoint_sequence"), row.getBoolean("partial"), row.getBoolean("deleted"),
                row.getObject("created_at", LocalDateTime.class), row.getString("model_name"),
                row.getString("finish_reason"), row.getString("generation_source"), row.getString("degraded_stages"));
    }

    private String encode(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not encode chat snapshot", error);
        }
    }

    static String id() { return UUID.randomUUID().toString(); }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "会话或聊天轮次不存在");
    }
}
