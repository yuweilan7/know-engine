package know.engine.chat.service;

import com.alibaba.druid.pool.DruidDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Blocking calls use separate, bounded pools. A timed-out call keeps its worker until the I/O exits. */
@Component
@Slf4j
public class OnlineChatWork {
    enum Stage {
        DB(4, 1800), INTENT(4, 1800), TITLE(1, 2500), REWRITE(4, 1400),
        EMBEDDING(4, 1300), SEARCH(6, 950), RERANK(3, 1100), GENERATION(8, 45000);

        final int workers;
        final Duration timeout;

        Stage(int workers, long timeoutMillis) {
            this.workers = workers;
            this.timeout = Duration.ofMillis(timeoutMillis);
        }
    }

    private final EnumMap<Stage, Lane> lanes = new EnumMap<>(Stage.class);
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();
    private final DruidDataSource database;
    private final ChatSettings settings;
    private long loggedDbWaits;

    OnlineChatWork(DataSource dataSource) {
        this(dataSource, new ChatSettings());
    }

    @Autowired
    OnlineChatWork(DataSource dataSource, ChatSettings settings) {
        this.settings = settings;
        database = dataSource instanceof DruidDataSource druid ? druid : null;
        if (database != null) {
            log.info("Druid limits maxActive={} borrowMs={} connectMs={} socketMs={} querySeconds={}",
                    database.getMaxActive(), database.getMaxWait(), database.getConnectTimeout(),
                    database.getSocketTimeout(), database.getQueryTimeout());
        }
        for (Stage stage : Stage.values()) {
            lanes.put(stage, new Lane(stage));
        }
    }

    <T> Mono<T> run(Stage stage, Duration remaining, Callable<T> action) {
        return run(stage, () -> remaining, action);
    }

    <T> Mono<T> run(Stage stage, Supplier<Duration> remaining, Callable<T> action) {
        return this.<T>execute(stage, remaining, null, null, sink -> {
            T result = action.call();
            if (result != null) sink.next(result);
        }).singleOrEmpty();
    }

    <T> Flux<T> stream(Duration remaining, Duration firstAnswer, Duration idle, Work<T> action) {
        return execute(Stage.GENERATION, () -> remaining, firstAnswer, idle, action);
    }

    private <T> Flux<T> execute(Stage stage, Supplier<Duration> remaining, Duration firstAnswer, Duration idle, Work<T> action) {
        return Flux.defer(() -> {
            Lane lane = lanes.get(stage);
            Duration limit = settings.getStageTimeouts().getOrDefault(stage, stage.timeout);
            Duration left = remaining.get();
            boolean phaseLimited = left.compareTo(limit) <= 0;
            Duration budget = phaseLimited ? left : limit;
            if (budget.isZero() || budget.isNegative()) {
                lane.timeouts.increment();
                return Flux.error(new StageTimeout(stage, phaseLimited));
            }
            long ticket = lane.circuit.enter();
            if (ticket < 0) {
                lane.skipped.increment();
                return Flux.error(new Unavailable("依赖暂不可用"));
            }
            Flux<T> source = Flux.create(sink -> {
                Call call = new Call(lane);
                long queuedAt = System.nanoTime();
                FutureTask<Void> task = new FutureTask<>(() -> {
                    if (!call.start()) return null;
                    CURRENT.set(call);
                    try {
                        if (System.nanoTime() - queuedAt >= budget.toNanos()) {
                            sink.error(new StageTimeout(stage, phaseLimited));
                        } else if (!sink.isCancelled()) {
                            action.run(sink);
                            sink.complete();
                        }
                    } catch (Throwable error) {
                        Exceptions.throwIfFatal(error);
                        if (!sink.isCancelled()) sink.error(error);
                    } finally {
                        CURRENT.remove();
                        call.finish();
                    }
                    return null;
                });
                sink.onCancel(() -> {
                    try {
                        call.cancel();
                    } finally {
                        task.cancel(CURRENT.get() != call);
                        lane.executor.remove(task);
                    }
                });
                try {
                    if (!task.isCancelled()) lane.executor.execute(task);
                } catch (RejectedExecutionException error) {
                    lane.rejected.increment();
                    sink.error(new Busy());
                }
            }, FluxSink.OverflowStrategy.ERROR);
            if (firstAnswer != null) {
                source = source.onBackpressureBuffer(256)
                        .timeout(Mono.delay(firstAnswer), ignored -> Mono.delay(idle));
            }
            return untilDeadline(source, budget, () -> {
                lane.timeouts.increment();
                return new StageTimeout(stage, phaseLimited);
            })
            .doOnComplete(() -> lane.circuit.success(ticket))
            .doOnError(error -> {
                lane.errors.increment();
                if (error instanceof TimeoutException) lane.timeouts.increment();
                if (error instanceof Busy || error instanceof StageTimeout timeout && timeout.phaseLimited
                        || error instanceof ChatFailure failure && !failure.dependencyFailure()) lane.circuit.abandon(ticket);
                else lane.circuit.failure(ticket);
                log.debug("Online stage {} failed ({})", stage, error.getClass().getSimpleName());
            })
            .doOnCancel(() -> lane.circuit.abandon(ticket));
        });
    }

    /** A timer value cancels the source. Report the timeout after upstream cancellation. */
    static <T> Flux<T> untilDeadline(Flux<T> source, Duration budget, Supplier<Unavailable> timeout) {
        return Flux.defer(() -> {
            var expired = new AtomicBoolean();
            return source.takeUntilOther(Mono.delay(budget).doOnNext(ignored -> expired.set(true)))
                    .concatWith(Mono.defer(() -> expired.get() ? Mono.error(timeout.get()) : Mono.empty()));
        });
    }

    /** Transport registers cancellation before doing network I/O, including before the first token. */
    public static Registration onTransportCancel(Runnable abort) {
        Call call = CURRENT.get();
        return call == null ? () -> {} : call.register(abort);
    }

    public interface Registration extends AutoCloseable {
        @Override void close();
    }

    static Runnable currentTransportAbort() {
        Call call = CURRENT.get();
        return call == null ? () -> {} : call::cancel;
    }

    void degraded(Stage stage) {
        lanes.get(stage).degraded.increment();
    }

    @Scheduled(fixedDelay = 60000)
    void logCounters() {
        if (database != null) {
            long waits = database.getNotEmptyWaitCount();
            int waiters = database.getWaitThreadCount();
            if (waits != loggedDbWaits || waiters > 0) {
                log.info("Druid active={} waiters={} borrowWaitsTotal={} borrowWaitMillisTotal={}",
                        database.getActiveCount(), waiters, waits, database.getNotEmptyWaitMillis());
                loggedDbWaits = waits;
            }
        }
        lanes.forEach((stage, lane) -> {
            long errors = lane.errors.sumThenReset();
            long timeouts = lane.timeouts.sumThenReset();
            long rejected = lane.rejected.sumThenReset();
            long skipped = lane.skipped.sumThenReset();
            long degraded = lane.degraded.sumThenReset();
            long started = lane.started.sumThenReset();
            int active = lane.executor.getActiveCount();
            if (started + errors + timeouts + rejected + skipped + degraded + active > 0) {
                log.info("Online stage={} attempts={} active={} queued={} cancelledStillRunning={} errors={} timeouts={} rejected={} circuitSkipped={} degraded={}",
                        stage, started, active, lane.executor.getQueue().size(), lane.cancelledRunning.sum(),
                        errors, timeouts, rejected, skipped, degraded);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        lanes.values().forEach(lane -> lane.executor.shutdownNow());
    }

    @FunctionalInterface
    interface Work<T> {
        void run(FluxSink<T> sink) throws Exception;
    }

    static class Unavailable extends RuntimeException {
        Unavailable(String message) { super(message); }
    }

    static final class Busy extends Unavailable {
        Busy() { super("服务繁忙，请稍后再试"); }
    }

    static final class StageTimeout extends Unavailable {
        final boolean phaseLimited;
        StageTimeout(Stage stage, boolean phaseLimited) {
            super(stage.name() + " 操作超时");
            this.phaseLimited = phaseLimited;
        }
    }

    private static final class Call {
        private final Lane lane;
        private boolean cancelled;
        private boolean running;
        private final HashSet<Runnable> aborts = new HashSet<>();

        Call(Lane lane) {
            this.lane = lane;
        }

        synchronized boolean start() {
            if (cancelled) return false;
            running = true;
            lane.started.increment();
            return true;
        }

        synchronized void finish() {
            if (cancelled && running) lane.cancelledRunning.decrement();
            running = false;
            aborts.clear();
        }

        Registration register(Runnable abort) {
            synchronized (this) {
                if (!cancelled) {
                    aborts.add(abort);
                    return () -> unregister(abort);
                }
            }
            abortSafely(abort);
            return () -> {};
        }

        private synchronized void unregister(Runnable abort) {
            aborts.remove(abort);
        }

        void cancel() {
            List<Runnable> snapshot;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                if (running) lane.cancelledRunning.increment();
                snapshot = List.copyOf(aborts);
                aborts.clear();
            }
            snapshot.forEach(Call::abortSafely);
        }

        private static void abortSafely(Runnable abort) {
            try {
                abort.run();
            } catch (RuntimeException error) {
                log.warn("Transport cancellation failed ({})", error.getClass().getSimpleName());
            }
        }
    }

    private static final class Lane {
        final ThreadPoolExecutor executor;
        final Circuit circuit = new Circuit();
        final LongAdder errors = new LongAdder();
        final LongAdder timeouts = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final LongAdder skipped = new LongAdder();
        final LongAdder degraded = new LongAdder();
        final LongAdder started = new LongAdder();
        final LongAdder cancelledRunning = new LongAdder();

        Lane(Stage stage) {
            executor = new ThreadPoolExecutor(stage.workers, stage.workers, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(stage.workers),
                    Thread.ofPlatform().daemon().name("online-" + stage.name().toLowerCase() + "-", 0).factory());
            executor.allowCoreThreadTimeOut(true);
        }
    }

    /** Three consecutive failures open the lane for ten seconds, followed by one probe. */
    private static final class Circuit {
        private long generation;
        private int failures;
        private long openedAt;
        private boolean probe;

        synchronized long enter() {
            if (failures >= 3) {
                if (probe || System.nanoTime() - openedAt < TimeUnit.SECONDS.toNanos(10)) return -1;
                probe = true;
            }
            return generation;
        }

        synchronized void success(long ticket) {
            if (ticket != generation) return;
            failures = 0;
            if (probe) generation++;
            probe = false;
        }

        synchronized void failure(long ticket) {
            if (ticket != generation) return;
            if (++failures >= 3) {
                openedAt = System.nanoTime();
                generation++;
            }
            probe = false;
        }

        synchronized void abandon(long ticket) {
            if (ticket == generation) probe = false;
        }
    }
}
