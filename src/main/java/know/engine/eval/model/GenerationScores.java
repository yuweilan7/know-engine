package know.engine.eval.model;

import lombok.Data;

/**
 * 单条用例的 LLM-as-Judge 评分（生成层 + 检索上下文质量）
 */
@Data
public class GenerationScores {

    // 忠实度分数
    private Double faithfulness;

    // 忠实度评分理由
    private String faithfulnessReason;

    // 相关性分数
    private Double relevancy;

    // 相关性评分理由
    private String relevancyReason;

    // 答案正确性分数
    private Double answerCorrectness;

    // 答案正确性评分理由
    private String answerCorrectnessReason;

    // 检索上下文精确率：召回片段与问题的相关程度
    private Double contextPrecision;

    // 上下文精确率评分理由
    private String contextPrecisionReason;

    // 检索上下文召回率：召回片段对标准答案信息的覆盖程度
    private Double contextRecall;

    // 上下文召回率评分理由
    private String contextRecallReason;
}
