package know.engine.chat.service;

final class ChatFailure extends OnlineChatWork.Unavailable {
    final Reason reason;

    enum Reason {
        SUCCESS, NO_EVIDENCE, USER_CANCEL, CLIENT_DISCONNECTED, NOT_STARTED, SERVER_STOPPING,
        PREPARATION_TIMEOUT, FIRST_ANSWER_TIMEOUT, STREAM_IDLE_TIMEOUT, TOTAL_TIMEOUT,
        RETRIEVAL_UNAVAILABLE, HISTORY_UNAVAILABLE, UPSTREAM_INTERRUPTED, UPSTREAM_PROTOCOL,
        EMPTY_ANSWER, CONTENT_FILTER, OUTPUT_LIMIT, SLOW_CONSUMER, CAPACITY_EXCEEDED,
        PERSISTENCE_UNAVAILABLE, RECOVERY_INCOMPLETE, INTERNAL_ERROR
    }

    ChatFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    boolean dependencyFailure() {
        return switch (reason) {
            case UPSTREAM_INTERRUPTED, UPSTREAM_PROTOCOL, EMPTY_ANSWER, FIRST_ANSWER_TIMEOUT, STREAM_IDLE_TIMEOUT -> true;
            default -> false;
        };
    }
}
