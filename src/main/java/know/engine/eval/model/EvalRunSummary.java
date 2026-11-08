package know.engine.eval.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评测批次列表项（轻量摘要）
 */
@Data
public class EvalRunSummary {

    // 评测运行 ID
    private String runId;

    // 数据集路径
    private String datasetPath;

    // 创建时间
    private LocalDateTime createdAt;

    // 用例总数
    private int totalCases;

    // 检索评测完成数
    private int evaluatedCases;

    // 生成评测完成数
    private int generationEvaluatedCases;

    // Hit@K 均值
    private Double hitAtK;

    // 忠实度均值
    private Double faithfulness;

    // 相关性均值
    private Double relevancy;

    // 答案正确性均值
    private Double answerCorrectness;

    // 上下文精确率均值
    private Double contextPrecision;

    // 上下文召回率均值
    private Double contextRecall;

    // 失败用例数
    private int failedCount;

    // 待人工复核用例数
    private int reviewCount;
}
