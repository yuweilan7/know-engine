package know.engine.eval.service;

import know.engine.eval.model.EvalBatchReport;
import know.engine.eval.model.EvalCaseResult;
import know.engine.eval.model.GenerationScores;
import know.engine.eval.model.RetrievalScores;
import org.springframework.stereotype.Service;

/**
 * 评测报告 CSV 导出
 */
@Service
public class EvalExportService {

    // 将批量报告转为 CSV 字符串
    public String toCsv(EvalBatchReport report) {
        // 拼接 CSV 内容
        StringBuilder csv = new StringBuilder();
        // 写入表头
        csv.append("case_id,question,success,latency_ms,hit_at_k,mrr,recall_at_k,context_precision,context_recall,faithfulness,relevancy,answer_correctness,error\n");
        // 逐条写入用例明细
        for (EvalCaseResult caseResult : report.getCaseResults()) {
            // 用例 ID
            csv.append(escape(caseResult.getCaseId())).append(',');
            // 问题文本
            csv.append(escape(caseResult.getQuestion())).append(',');
            // 是否成功
            csv.append(caseResult.isSuccess()).append(',');
            // 耗时
            csv.append(caseResult.getLatencyMs()).append(',');

            // 检索评分
            RetrievalScores retrieval = caseResult.getRetrievalScores();
            // Hit@K
            csv.append(retrieval != null ? retrieval.isHitAtK() : "").append(',');
            // MRR
            csv.append(retrieval != null ? retrieval.getMrr() : "").append(',');
            // Recall@K
            csv.append(retrieval != null ? retrieval.getRecallAtK() : "").append(',');

            // 生成评分
            GenerationScores generation = caseResult.getGenerationScores();
            // 上下文精确率
            csv.append(generation != null && generation.getContextPrecision() != null ? generation.getContextPrecision() : "").append(',');
            // 上下文召回率
            csv.append(generation != null && generation.getContextRecall() != null ? generation.getContextRecall() : "").append(',');
            // 忠实度
            csv.append(generation != null && generation.getFaithfulness() != null ? generation.getFaithfulness() : "").append(',');
            // 相关性
            csv.append(generation != null && generation.getRelevancy() != null ? generation.getRelevancy() : "").append(',');
            // 答案正确性
            csv.append(generation != null && generation.getAnswerCorrectness() != null ? generation.getAnswerCorrectness() : "").append(',');
            // 错误信息并换行
            csv.append(escape(caseResult.getError())).append('\n');
        }
        // 返回完整 CSV
        return csv.toString();
    }

    // CSV 字段转义：含逗号/引号/换行时加双引号
    private String escape(String value) {
        // null 输出空串
        if (value == null) {
            // 空字段
            return "";
        }
        // 双引号转义为两个双引号
        String escaped = value.replace("\"", "\"\"");
        // 需要包裹引号的特殊字符
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            // 用双引号包裹
            return "\"" + escaped + "\"";
        }
        // 无需转义直接返回
        return escaped;
    }
}
