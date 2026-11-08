package know.engine.eval.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次批量评测的汇总报告
 */
@Data
public class EvalBatchReport {

    // 本次评测运行 ID
    private String runId;

    // 数据集路径
    private String datasetPath;

    // 评测配置
    private EvalConfig config;

    // 用例总数
    private int totalCases;

    /**
     * 测试集中 含检索 Ground Truth 且成功评分的用例数
     */
    private int evaluatedCases;

    /**
     * 成功完成 LLM Judge 评分的用例数
     */
    private int generationEvaluatedCases;

    // 报告创建时间
    private LocalDateTime createdAt;

    // 检索指标汇总
    private RetrievalMetricsSummary retrieval;

    // 生成指标汇总
    private GenerationMetricsSummary generation;

    // 上下文指标汇总
    private ContextMetricsSummary context;

    // 全部用例明细结果
    private List<EvalCaseResult> caseResults = new ArrayList<>();

    // 失败用例 ID 列表
    private List<String> failedCaseIds = new ArrayList<>();

    /**
     * 低分或推理失败，建议人工复核的用例 ID
     */
    private List<String> reviewCaseIds = new ArrayList<>();

    @Data
    // 检索层指标汇总
    public static class RetrievalMetricsSummary {

        // Hit@K 均值
        private double hitAtK;

        // MRR 均值
        private double mrr;

        // Recall@K 均值
        private double recallAtK;

        // 平均延迟（毫秒）
        private long avgLatencyMs;

        // P50 延迟（毫秒）
        private long p50LatencyMs;

        // P95 延迟（毫秒）
        private long p95LatencyMs;
    }

    @Data
    // 生成层指标汇总
    public static class GenerationMetricsSummary {

        // 忠实度均值
        private double faithfulness;

        // 相关性均值
        private double relevancy;

        // 答案正确性均值
        private double answerCorrectness;

        // 忠实度有效样本数
        private int faithfulnessCount;

        // 相关性有效样本数
        private int relevancyCount;

        // 答案正确性有效样本数
        private int answerCorrectnessCount;
    }

    @Data
    // 上下文质量指标汇总
    public static class ContextMetricsSummary {

        // 上下文精确率均值
        private double contextPrecision;

        // 上下文召回率均值
        private double contextRecall;

        // 精确率有效样本数
        private int contextPrecisionCount;

        // 召回率有效样本数
        private int contextRecallCount;
    }
}
