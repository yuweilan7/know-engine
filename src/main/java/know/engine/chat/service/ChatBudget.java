package know.engine.chat.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static know.engine.chat.service.ChatFailure.Reason.*;

/** 用单调时钟计算本轮剩余时间。阶段降级前再次核对截止时间和取消信号。 */
final class ChatBudget {
    private final ChatSettings settings;
    private final LongSupplier clock;
    private final long start;
    private final AtomicReference<ChatFailure.Reason> stopped = new AtomicReference<>();

    ChatBudget(ChatSettings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        this.start = clock.getAsLong();
    }

    Duration preparation() {
        requireTotal();
        Duration remaining = remaining(settings.getPreparationTimeout());
        if (remaining.isZero()) throw new ChatFailure(PREPARATION_TIMEOUT, "准备回答超时");
        return remaining;
    }

    Duration firstAnswer() { return remaining(settings.getFirstAnswerTimeout()); }
    Duration total() { return remaining(settings.getTotalTimeout()); }
    long elapsedMillis() { return Duration.ofNanos(clock.getAsLong() - start).toMillis(); }

    void requireTotal() {
        ChatFailure.Reason reason = stopped.get();
        if (reason != null) throw new ChatFailure(reason, "本轮已结束");
        if (total().isZero()) throw new ChatFailure(TOTAL_TIMEOUT, "本轮回答超时");
    }

    void stop(ChatFailure.Reason reason) { stopped.compareAndSet(null, reason); }

    void allowPreparationFallback(Throwable error) {
        preparation();
        if (error instanceof ChatFailure failure) throw failure;
        if (error instanceof OnlineChatWork.StageTimeout timeout && timeout.phaseLimited) {
            throw new ChatFailure(PREPARATION_TIMEOUT, "准备回答超时");
        }
    }

    private Duration remaining(Duration limit) {
        return Duration.ofNanos(Math.max(0, limit.toNanos() - (clock.getAsLong() - start)));
    }
}
