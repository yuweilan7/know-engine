package know.engine.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import know.engine.chat.entity.ChatMessage;
import know.engine.chat.entity.ChatMessage.RagReference;
import know.engine.chat.memory.DatabaseChatMemoryStore;
import know.engine.ai.service.TitleSummaryService;
import know.engine.common.DefaultUser;
import know.engine.rag.PromptBudget;
import know.engine.rag.RagContext.Prepared;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.AbstractQueue;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static know.engine.chat.service.ChatFailure.Reason.*;
import static know.engine.chat.service.ChatRunStore.Status.*;
import static know.engine.chat.service.OnlineChatWork.Stage.DB;
import static know.engine.chat.service.OnlineChatWork.Stage.TITLE;

/** 在线轮次的唯一执行所有者，协调准入、取消、阶段检查点和断线后的终态保存。 */
@Service
@Slf4j
public class ChatRunService {
    /**
     * clientRequestId 由调用方生成，重试复用。conversationId 为空时创建会话，userId 沿用 DefaultUser 约定。
     * content 是原问题，mode 为空时按 AUTO 路由。
     */
    public record CreateRequest(String clientRequestId, String conversationId, String content,
                                ChatService.Mode mode, String userId) {}
    /** 轮次元数据。两条消息在创建事务中已有 ID，助手消息此时可以只有占位。 */
    public record Meta(String runId, String conversationId, String userMessageId, String assistantMessageId,
                       ChatService.Mode mode, String status) {}
    public record State(Meta run, String answer, long checkpointSequence, boolean partial, String reasonCode,
                        List<String> degradedStages, String finishReason, String generationSource,
                        List<RagReference> references) {}
    public record Cancellation(String status, boolean accepted, boolean persisted) {}
    public record Message(String messageId, String type, String content, List<RagReference> ragReferences,
                          String runId, String status, boolean partial, String reasonCode, LocalDateTime createdAt) {}
    /** 线上事件只在该轮出口分配序号。SSE comment 心跳不参与序号和回答内容。 */
    public record ChatEvent(int protocolVersion, String runId, long sequence, String type, Instant timestamp, Object payload) {}
    public record End(String status, String reasonCode, boolean persisted, List<String> degradedStages,
                      String finishReason, String generationSource) {}
    public record StreamError(String code, boolean persisted) {}

    private static final ChatEvent HEARTBEAT = new ChatEvent(1, null, 0, "heartbeat", null, null);
    private static final int CHECKPOINT_BYTES = 4096;

    private final ChatRunStore store;
    private final ChatService chat;
    private final ChatMessageService messages;
    private final ChatConversationService conversations;
    private final DatabaseChatMemoryStore memory;
    private final TitleSummaryService titles;
    private final OnlineChatWork work;
    private final ChatSettings settings;
    private final ObjectMapper json;
    private final String instance = ChatRunStore.id();
    private final Semaphore readySlots = new Semaphore(0);
    private final Semaphore runningSlots;
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ThreadPoolExecutor finalizers;
    private final ThreadPoolExecutor checkpoints;
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(2,
            Thread.ofPlatform().daemon().name("chat-run-timer-", 0).factory());

    ChatRunService(ChatRunStore store, ChatService chat, ChatMessageService messages, ChatConversationService conversations,
                   DatabaseChatMemoryStore memory, TitleSummaryService titles, OnlineChatWork work, ChatSettings settings,
                   ObjectMapper json) {
        this.store = store;
        this.chat = chat;
        this.messages = messages;
        this.conversations = conversations;
        this.memory = memory;
        this.titles = titles;
        this.work = work;
        this.settings = settings;
        this.json = json;
        this.runningSlots = new Semaphore(settings.getMaxConcurrent());
        this.finalizers = pool("chat-finalize", 4, settings.getMaxConcurrent());
        this.checkpoints = pool("chat-checkpoint", 2, settings.getMaxConcurrent());
        timers.setRemoveOnCancelPolicy(true);
        timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    private static ThreadPoolExecutor pool(String name, int workers, int queue) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue),
                Thread.ofPlatform().daemon().name(name + "-", 0).factory());
    }

    @PostConstruct
    void recoverPreviousProcess() {
        // Deployment runs one Java instance against this chat schema.
        store.interruptedRuns().forEach(store::recover);
        store.expiredReady(settings.getReadyTtl()).forEach(run -> store.cancelReady(run, NOT_STARTED));
        int existing = store.readyCount();
        if (existing > settings.getMaxPending()) throw new IllegalStateException("Too many persisted READY chat runs");
        readySlots.release(settings.getMaxPending() - existing);
    }

    public Meta create(CreateRequest request) {
        ensureOpen();
        if (request == null) throw problem(HttpStatus.BAD_REQUEST, "缺少请求");
        try {
            chat.validateQuestion(request.content());
        } catch (IllegalArgumentException error) {
            throw problem(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        String userId = userId(request.userId());
        String requestId = request.clientRequestId();
        if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw problem(HttpStatus.BAD_REQUEST, "clientRequestId 格式无效");
        }
        String conversationId = request.conversationId();
        if (conversationId != null && conversationId.isBlank()) conversationId = null;
        if (conversationId != null && conversationId.length() > 64) throw problem(HttpStatus.BAD_REQUEST, "会话 ID 无效");
        var mode = request.mode() == null ? ChatService.Mode.AUTO : request.mode();
        String fingerprint = fingerprint(conversationId, request.content(), mode);
        var command = new ChatRunStore.Command(userId, requestId, conversationId, request.content(), mode, fingerprint);
        var existing = store.lookup(command);
        if (existing != null) return meta(existing, null);
        if (!readySlots.tryAcquire()) throw problem(HttpStatus.TOO_MANY_REQUESTS, "待启动回答已满");
        boolean retained = false;
        try {
            var created = store.create(command);
            retained = created.newRun();
            return meta(created.run(), null);
        } finally {
            if (!retained) readySlots.release();
        }
    }

    public Flux<ChatEvent> open(String id, String user) {
        ensureOpen();
        var run = store.owned(id, userId(user));
        if (run.status() != READY) throw conflict();
        if (!runningSlots.tryAcquire()) throw problem(HttpStatus.TOO_MANY_REQUESTS, "当前回答并发已满");
        var handle = new ActiveRun(run);
        if (active.putIfAbsent(id, handle) != null) {
            runningSlots.release();
            throw conflict();
        }
        boolean started = false;
        try {
            synchronized (handle) {
                ensureOpen();
                if (!store.start(run, instance, settings.getReadyTtl(), settings.getTotalTimeout())) throw conflict();
                readySlots.release();
                handle.budget = new ChatBudget(settings, System::nanoTime);
                handle.totalTimer = timers.schedule(() -> handle.finish(TIMED_OUT, TOTAL_TIMEOUT, null),
                        settings.getTotalTimeout().toNanos(), TimeUnit.NANOSECONDS);
                started = true;
            }
            // HTTP 处理阶段先拿准入，才能给竞争请求返回真实 409/429。首次订阅才启动依赖和模型。
            return handle.events.asFlux().doOnSubscribe(ignored -> handle.start())
                    .doOnCancel(() -> handle.finish(CANCELLED, CLIENT_DISCONNECTED, null));
        } finally {
            if (!started && active.remove(id, handle)) runningSlots.release();
        }
    }

    public Cancellation cancel(String id, String user) {
        String owner = userId(user);
        var run = store.owned(id, owner);
        if (run.status().terminal()) return new Cancellation(run.status().name(), false, true);
        var handle = active.get(id);
        if (handle != null && handle.finish(CANCELLED, USER_CANCEL, null)) {
            return new Cancellation(FINALIZING.name(), true, false);
        }
        if (run.status() == READY && store.cancelReady(run, USER_CANCEL)) {
            readySlots.release();
            return new Cancellation(CANCELLED.name(), true, true);
        }
        if (handle == null) {
            // READY 条件更新可能输给同时到达的打开流请求，此时交给刚注册的活动轮次处理。
            handle = active.get(id);
            if (handle != null && handle.finish(CANCELLED, USER_CANCEL, null)) {
                return new Cancellation(FINALIZING.name(), true, false);
            }
        }
        var current = store.owned(id, owner);
        boolean finishing = handle != null && handle.outcome != null && !current.status().terminal();
        return new Cancellation(finishing ? FINALIZING.name() : current.status().name(), false, current.status().terminal());
    }

    public State state(String id, String user) {
        var run = store.owned(id, userId(user));
        var message = messages.getByMessageId(run.assistantMessageId());
        var handle = active.get(id);
        return new State(meta(run, handle), message.getContent(), run.checkpointSequence(), run.partial(), run.reason(),
                decodeWarnings(run.degradedJson()), run.finishReason(), run.generationSource(), message.getRagReferences());
    }

    public List<Message> history(String conversationId, String user) {
        store.requireConversation(userId(user), conversationId, false);
        Map<String, ChatRunStore.Run> runs = store.forConversation(conversationId).stream()
                .collect(Collectors.toMap(ChatRunStore.Run::id, Function.identity()));
        return messages.getMessagesByConversationId(conversationId).stream().map(message -> {
            var run = message.getRunId() == null ? null : runs.get(message.getRunId());
            return new Message(message.getMessageId(), message.getType().name(), message.getContent(),
                    message.getRagReferences(), message.getRunId(), run == null ? null : meta(run, active.get(run.id())).status(),
                    run != null && run.partial(), run == null ? null : run.reason(), message.getCreatedAt());
        }).toList();
    }

    public void deleteConversation(String conversationId, String user) {
        store.deleteConversation(userId(user), conversationId);
    }

    @Scheduled(fixedDelay = 500)
    void maintain() {
        if (closing.get()) return;
        active.values().forEach(handle -> {
            handle.checkpoint(false);
            if (handle.needsRecovery && !handle.finalizerRunning) {
                try {
                    if (store.recover(handle.run)) release(handle);
                } catch (RuntimeException error) {
                    log.warn("Chat recovery deferred runId={} error={}", handle.run.id(), error.getClass().getSimpleName());
                }
            }
        });
    }

    @Scheduled(fixedDelay = 1000)
    void expireReady() {
        if (closing.get() || readySlots.availablePermits() == settings.getMaxPending()) return;
        for (var run : store.expiredReady(settings.getReadyTtl())) {
            if (store.cancelReady(run, NOT_STARTED)) readySlots.release();
        }
    }

    @PreDestroy
    void stop() {
        closing.set(true);
        active.values().forEach(handle -> handle.finish(CANCELLED, SERVER_STOPPING, null));
        checkpoints.shutdownNow();
        finalizers.shutdown();
        try {
            if (!finalizers.awaitTermination(settings.getFinalizeTimeout().toMillis(), TimeUnit.MILLISECONDS)) finalizers.shutdownNow();
        } catch (InterruptedException error) {
            finalizers.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            timers.shutdownNow();
        }
    }

    private void release(ActiveRun handle) {
        if (active.remove(handle.run.id(), handle)) runningSlots.release();
    }

    private Meta meta(ChatRunStore.Run run, ActiveRun handle) {
        return new Meta(run.id(), run.conversationId(), run.userMessageId(), run.assistantMessageId(), run.mode(),
                handle != null && handle.outcome != null && !run.status().terminal() ? FINALIZING.name() : run.status().name());
    }

    private static String userId(String user) {
        String id = DefaultUser.userIdOrDefault(user);
        if (id.length() > 64 || !id.equals(id.strip())) throw problem(HttpStatus.BAD_REQUEST, "用户 ID 无效");
        return id;
    }

    private static String fingerprint(String conversation, String question, ChatService.Mode mode) {
        String normalized = mode.name() + "\0" + (conversation == null ? "" : conversation.length() + ":" + conversation)
                + "\0" + question;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private List<String> decodeWarnings(String value) {
        if (value == null) return List.of();
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not decode chat warnings", error);
        }
    }

    private void ensureOpen() {
        if (closing.get()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "服务正在停止");
    }

    private static ResponseStatusException conflict() {
        return problem(HttpStatus.CONFLICT, "轮次已启动或已结束，请查询状态");
    }

    private static ResponseStatusException problem(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private final class ActiveRun {
        private final ChatRunStore.Run run;
        private final EventQueue queue = new EventQueue();
        private final Sinks.Many<ChatEvent> events = Sinks.many().unicast().onBackpressureBuffer(queue);
        private final StringBuilder answer = new StringBuilder();
        private final TreeSet<String> warnings = new TreeSet<>();
        private final BaseSubscriber<ChatService.ExecutionEvent> upstream;
        private ChatBudget budget;
        private Prepared context;
        private long sequence;
        private long revision;
        private long savedRevision;
        private int answerBytes;
        private int lastSavedBytes;
        private long lastSaveNanos = System.nanoTime();
        private long firstAnswerMs = -1;
        private String stage = "PREPARATION";
        private ScheduledFuture<?> totalTimer;
        private ScheduledFuture<?> heartbeat;
        private reactor.core.Disposable titleTask;
        private boolean subscribed;
        private boolean checkpointInFlight;
        private boolean streamFinished;
        private volatile boolean needsRecovery;
        private volatile boolean finalizerRunning;
        private volatile ChatRunStore.Outcome outcome;

        ActiveRun(ChatRunStore.Run run) {
            this.run = run;
            upstream = new BaseSubscriber<>() {
                @Override protected void hookOnNext(ChatService.ExecutionEvent event) { accept(event); }
                @Override protected void hookOnError(Throwable error) {
                    var reason = ChatService.reason(error);
                    if (reason == INTERNAL_ERROR) {
                        log.warn("Chat execution failed runId={} error={}", run.id(), error.getClass().getSimpleName());
                    }
                    finish(statusFor(reason), reason, null);
                }
                @Override protected void hookOnComplete() { finish(FAILED, UPSTREAM_PROTOCOL, null); }
            };
        }

        void start() {
            synchronized (this) {
                if (subscribed || outcome != null) return;
                subscribed = true;
                emit("meta", new Meta(run.id(), run.conversationId(), run.userMessageId(), run.assistantMessageId(),
                        run.mode(), RUNNING.name()));
                heartbeat = timers.scheduleAtFixedRate(this::heartbeat, settings.getHeartbeatInterval().toNanos(),
                        settings.getHeartbeatInterval().toNanos(), TimeUnit.NANOSECONDS);
            }
            var input = work.run(DB, budget::preparation, () -> {
                ChatMessage userMessage = messages.getByMessageId(run.userMessageId());
                if (userMessage == null) throw new IllegalStateException("Missing chat user message");
                return new ChatService.Request(userMessage.getContent(), run.mode());
            }).onErrorMap(this::historyFailure);
            var history = work.run(DB, budget::preparation, () -> memory.loadHistory(run.conversationId(), run.userMessageId()))
                    .onErrorMap(this::historyFailure);
            input.flatMapMany(request -> {
                startTitle(request.question());
                return chat.execute(request, history, budget, this::accept, this::recordWarning, this::saveContext);
            }).subscribe(upstream);
        }

        private Throwable historyFailure(Throwable error) {
            budget.allowPreparationFallback(error);
            return new ChatFailure(HISTORY_UNAVAILABLE, "无法加载对话历史");
        }

        private void startTitle(String question) {
            // One owned best-effort subscription per newly started conversation.
            var task = work.run(DB, () -> DB.timeout, () -> store.takeTitle(run)).filter(Boolean::booleanValue)
                    .flatMap(ignored -> work.run(TITLE, () -> TITLE.timeout, () -> titles.generateTitle(question)))
                    .filter(title -> !title.isBlank())
                    .flatMap(title -> work.run(DB, () -> DB.timeout, () -> {
                        int end = title.offsetByCodePoints(0, Math.min(200, title.codePointCount(0, title.length())));
                        return conversations.updateTitle(run.conversationId(), title.substring(0, end));
                    })).subscribe(ignored -> {}, error -> work.degraded(TITLE));
            synchronized (this) {
                if (outcome == null) titleTask = task;
                else task.dispose();
            }
        }

        private synchronized void recordWarning(ChatService.Warning warning) {
            if (outcome == null) warnings.add(warning.code());
        }

        private Mono<Void> saveContext(Prepared prepared) {
            return Mono.defer(() -> {
                ChatRunStore.Snapshot snapshot;
                synchronized (this) {
                    if (outcome != null) return Mono.never();
                    context = prepared;
                    revision++;
                    snapshot = snapshot(null);
                }
                return work.run(DB, budget::preparation, () -> {
                    store.checkpoint(run, snapshot);
                    return true;
                }).doOnNext(ignored -> saved(snapshot, 0)).then().onErrorMap(error -> {
                    budget.allowPreparationFallback(error);
                    return new ChatFailure(PERSISTENCE_UNAVAILABLE, "无法保存本轮资料");
                });
            });
        }

        private void accept(ChatService.ExecutionEvent event) {
            if (event instanceof ChatService.Done done) {
                finish(statusFor(done.reason()), done.reason(), done);
                return;
            }
            boolean delivered;
            synchronized (this) {
                if (outcome != null) return;
                delivered = switch (event) {
                    case ChatService.Progress progress -> {
                        stage = progress.stage();
                        yield emit("progress", progress);
                    }
                    case ChatService.Warning warning -> emit("warning", warning);
                    case ChatService.References refs -> emit("references", refs.context());
                    case ChatService.Delta delta -> {
                        if (firstAnswerMs < 0) firstAnswerMs = budget.elapsedMillis();
                        answer.append(delta.text());
                        answerBytes += PromptBudget.bytes(delta.text());
                        revision++;
                        yield emit("delta", delta);
                    }
                    case ChatService.Done ignored -> throw new IllegalStateException();
                };
            }
            if (!delivered) finish(FAILED, SLOW_CONSUMER, null);
            else if (event instanceof ChatService.Delta) checkpoint(true);
        }

        private synchronized void heartbeat() {
            if (outcome == null && queue.isEmpty()) events.tryEmitNext(HEARTBEAT);
        }

        private boolean emit(String type, Object payload) {
            var result = events.tryEmitNext(new ChatEvent(1, run.id(), ++sequence, type, Instant.now(), payload));
            return result.isSuccess();
        }

        /** 文本新增 4 KiB 或距上次写入约一秒时保存，尚未完成的检查点始终只有一个。 */
        void checkpoint(boolean onlyIfLarge) {
            ChatRunStore.Snapshot snapshot;
            int size;
            synchronized (this) {
                if (outcome != null || !subscribed || checkpointInFlight || answerBytes == lastSavedBytes
                        || answerBytes - lastSavedBytes < CHECKPOINT_BYTES
                            && (onlyIfLarge || System.nanoTime() - lastSaveNanos < settings.getCheckpointInterval().toNanos())) return;
                checkpointInFlight = true;
                snapshot = snapshot(null);
                size = answerBytes;
            }
            try {
                checkpoints.execute(() -> {
                    try {
                        store.checkpoint(run, snapshot);
                        saved(snapshot, size);
                    } catch (RuntimeException error) {
                        finish(FAILED, PERSISTENCE_UNAVAILABLE, null);
                    } finally {
                        synchronized (this) { checkpointInFlight = false; }
                    }
                });
            } catch (RejectedExecutionException error) {
                synchronized (this) { checkpointInFlight = false; }
                finish(FAILED, PERSISTENCE_UNAVAILABLE, null);
            }
        }

        private synchronized void saved(ChatRunStore.Snapshot snapshot, int bytes) {
            if (snapshot.sequence() <= savedRevision) return;
            savedRevision = snapshot.sequence();
            lastSavedBytes = bytes;
            lastSaveNanos = System.nanoTime();
        }

        private ChatRunStore.Snapshot snapshot(ChatService.Done done) {
            return new ChatRunStore.Snapshot(answer.toString(), context, revision, stage, List.copyOf(warnings),
                    done == null ? null : done.modelName(), done == null ? null : done.finishReason(),
                    done == null ? null : done.generationSource(), done == null ? null : done.tokenCount());
        }

        /** 完成、取消、超时和断线竞争同一个终态，冻结快照后在锁外取消上游。 */
        boolean finish(ChatRunStore.Status status, ChatFailure.Reason reason, ChatService.Done done) {
            reactor.core.Disposable title;
            synchronized (this) {
                if (outcome != null || budget == null) return false;
                if (status != CANCELLED && budget.total().isZero()) {
                    status = TIMED_OUT;
                    reason = TOTAL_TIMEOUT;
                }
                outcome = new ChatRunStore.Outcome(status, reason, ChatRunStore.id(), snapshot(done));
                if (done == null) budget.stop(reason);
                title = titleTask;
                if (totalTimer != null) totalTimer.cancel(false);
                if (heartbeat != null) heartbeat.cancel(false);
            }
            if (done == null) upstream.cancel();
            if (title != null && status == CANCELLED) title.dispose();
            submitFinalizer();
            return true;
        }

        /** 独立执行池持有收尾任务，SSE 下游取消后仍保存，只有提交后才发送 end。 */
        private void submitFinalizer() {
            long deadline = System.nanoTime() + settings.getFinalizeTimeout().toNanos();
            var timeout = timers.schedule(() -> completeStream(null), settings.getFinalizeTimeout().toNanos(), TimeUnit.NANOSECONDS);
            finalizerRunning = true;
            try {
                finalizers.execute(() -> {
                    try {
                        for (int attempt = 0; attempt < 2 && System.nanoTime() < deadline; attempt++) {
                            try {
                                var saved = store.finish(run, outcome);
                                timeout.cancel(false);
                                completeStream(saved);
                                release(this);
                                return;
                            } catch (RuntimeException error) {
                                log.warn("Chat persistence attempt={} runId={} error={}", attempt + 1,
                                        run.id(), error.getClass().getSimpleName());
                            }
                        }
                        needsRecovery = true;
                        completeStream(null);
                    } finally {
                        finalizerRunning = false;
                    }
                });
            } catch (RejectedExecutionException error) {
                timeout.cancel(false);
                needsRecovery = true;
                finalizerRunning = false;
                completeStream(null);
            }
        }

        private synchronized void completeStream(ChatRunStore.Run saved) {
            if (streamFinished) return;
            streamFinished = true;
            if (saved == null) {
                emit("error", new StreamError(PERSISTENCE_UNAVAILABLE.name(), false));
            } else {
                emit("end", new End(saved.status().name(), saved.reason(), true, outcome.snapshot().degraded(),
                        saved.finishReason(), saved.generationSource()));
                log.info("Chat runId={} conversationId={} stage={} status={} reason={} model={} firstAnswerMs={} elapsedMs={}",
                        run.id(), run.conversationId(), stage, saved.status(), saved.reason(), saved.modelName(),
                        firstAnswerMs, budget.elapsedMillis());
            }
            events.tryEmitComplete();
        }
    }

    private static ChatRunStore.Status statusFor(ChatFailure.Reason reason) {
        return switch (reason) {
            case SUCCESS, NO_EVIDENCE -> COMPLETED;
            case USER_CANCEL, CLIENT_DISCONNECTED, SERVER_STOPPING, NOT_STARTED -> CANCELLED;
            case PREPARATION_TIMEOUT, FIRST_ANSWER_TIMEOUT, STREAM_IDLE_TIMEOUT, TOTAL_TIMEOUT -> TIMED_OUT;
            default -> FAILED;
        };
    }

    private final class EventQueue extends AbstractQueue<ChatEvent> {
        // 只有慢写出客户端才积压事件。按序列化后的 UTF-8 大小和事件数双重限制。
        private final ArrayDeque<Buffered> pending = new ArrayDeque<>();
        private int bytes;

        @Override
        public synchronized boolean offer(ChatEvent event) {
            if (pending.size() >= settings.getMaxBufferedEvents()) return false;
            int size;
            try {
                size = event == HEARTBEAT ? 32 : json.writeValueAsBytes(event).length + 128;
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Could not encode chat event", error);
            }
            if (size > settings.getMaxEventBytes() - bytes) return false;
            pending.addLast(new Buffered(event, size));
            bytes += size;
            return true;
        }

        @Override public synchronized ChatEvent poll() {
            var next = pending.pollFirst();
            if (next == null) return null;
            bytes -= next.bytes();
            return next.event();
        }

        @Override public synchronized ChatEvent peek() {
            var next = pending.peekFirst();
            return next == null ? null : next.event();
        }

        @Override public synchronized Iterator<ChatEvent> iterator() {
            return pending.stream().map(Buffered::event).toList().iterator();
        }

        @Override public synchronized int size() { return pending.size(); }

        @Override public synchronized void clear() {
            pending.clear();
            bytes = 0;
        }

        private record Buffered(ChatEvent event, int bytes) {}
    }
}
