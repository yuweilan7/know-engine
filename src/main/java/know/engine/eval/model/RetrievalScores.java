package know.engine.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条用例的检索层评分
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalScores {

    /**
     * Hit@K：Top-K 是否命中任一 Ground Truth
     */
    private boolean hitAtK;

    /**
     * MRR：第一个正确结果的倒数排名
     */
    private double mrr;

    /**
     * Recall@K：Ground Truth 在 Top-K 中的召回比例
     */
    private double recallAtK;
}
