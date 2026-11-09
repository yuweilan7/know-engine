package know.engine.chat.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties("know-engine.chat")
@Getter
@Setter
public class ChatSettings {
    private int maxConcurrent = 8;
    private int maxPending = 32;
    private Duration preparationTimeout = Duration.ofSeconds(6);
    private Duration firstAnswerTimeout = Duration.ofSeconds(12);
    private Duration streamIdleTimeout = Duration.ofSeconds(10);
    private Duration totalTimeout = Duration.ofSeconds(45);
    private Duration finalizeTimeout = Duration.ofSeconds(3);
    private Duration readyTtl = Duration.ofSeconds(60);
    private Duration heartbeatInterval = Duration.ofSeconds(10);
    private Duration checkpointInterval = Duration.ofSeconds(1);
    private int maxQuestionBytes = 4096;
    private int promptBudgetBytes = 32768;
    private int outputReserveBytes = 8192;
    private int safetyReserveBytes = 2048;
    private int maxAnswerBytes = 32768;
    private int maxEventBytes = 1048576;
    private int maxBufferedEvents = 256;
    private int rerankMaxCandidates = 20;
    private boolean vectorEnabled = true;
    private boolean rerankEnabled = true;
    private Double rerankThreshold;
    private String rerankThresholdModel;
    private Map<OnlineChatWork.Stage, Duration> stageTimeouts = new EnumMap<>(OnlineChatWork.Stage.class);

    public int inputByteLimit() {
        return promptBudgetBytes - outputReserveBytes - safetyReserveBytes;
    }

    @PostConstruct
    void validate() {
        if (maxConcurrent < 1 || maxPending < 1 || !positive(preparationTimeout)
                || firstAnswerTimeout.compareTo(preparationTimeout) <= 0 || totalTimeout.compareTo(firstAnswerTimeout) <= 0
                || !positive(streamIdleTimeout) || !positive(finalizeTimeout) || !positive(readyTtl)
                || !positive(heartbeatInterval) || !positive(checkpointInterval)
                || maxQuestionBytes < 1 || outputReserveBytes < 1 || safetyReserveBytes < 1
                || inputByteLimit() <= maxQuestionBytes + 1024 || maxAnswerBytes < 4
                || maxEventBytes < 4096 || maxBufferedEvents < 4 || rerankMaxCandidates < 1 || rerankMaxCandidates > 20
                || stageTimeouts.values().stream().anyMatch(timeout -> !positive(timeout))
                || rerankThreshold != null && (!Double.isFinite(rerankThreshold) || rerankThresholdModel == null)) {
            throw new IllegalArgumentException("Invalid know-engine.chat limits");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
