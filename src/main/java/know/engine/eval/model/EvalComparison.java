package know.engine.eval.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 两次评测批次的指标对比
 */
@Data
public class EvalComparison {

    // 基线运行 ID
    private String baselineRunId;

    // 候选运行 ID
    private String candidateRunId;

    // Hit@K 差值
    private MetricDelta hitAtK;

    // MRR 差值
    private MetricDelta mrr;

    // Recall@K 差值
    private MetricDelta recallAtK;

    // 忠实度差值
    private MetricDelta faithfulness;

    // 相关性差值
    private MetricDelta relevancy;

    // 答案正确性差值
    private MetricDelta answerCorrectness;

    // 上下文精确率差值
    private MetricDelta contextPrecision;

    // 上下文召回率差值
    private MetricDelta contextRecall;

    // 平均延迟差值
    private MetricDelta avgLatencyMs;

    // 相对基线提升的用例
    private List<String> improvedCaseIds = new ArrayList<>();

    // 相对基线回退的用例
    private List<String> regressedCaseIds = new ArrayList<>();

    @Data
    // 单个指标的基线/候选/差值
    public static class MetricDelta {
        // 基线值
        private Double baseline;
        // 候选值
        private Double candidate;
        // 候选减基线的差值
        private Double delta;
    }
}
