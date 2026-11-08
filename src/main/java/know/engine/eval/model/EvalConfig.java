package know.engine.eval.model;

import lombok.Data;

/**
 * 单次评测运行配置
 */
@Data
public class EvalConfig {

    /**
     * 检索指标计算的 Top-K，默认 5（与 RAG maxResults 对齐）
     */
    private int topK = 5;

    /**
     * 并发度，Phase 1 默认串行
     */
    private int concurrency = 1;

    /**
     * 是否启用 LLM-as-Judge 生成层评分
     */
    private boolean enableLlmJudge = true;

    /**
     * Judge 分数低于该阈值的用例进入 reviewCaseIds（faithfulness / relevancy 等）
     */
    private double reviewScoreThreshold = 0.6;

    /**
     * MRR 低于该阈值的用例进入 reviewCaseIds
     */
    private double reviewMrrThreshold = 0.3;

    /**
     * Recall@K 低于该阈值的用例进入 reviewCaseIds
     */
    private double reviewRecallAtKThreshold = 0.3;
}