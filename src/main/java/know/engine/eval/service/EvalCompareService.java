package know.engine.eval.service;

import know.engine.eval.model.EvalBatchReport;
import know.engine.eval.model.EvalCaseResult;
import know.engine.eval.model.EvalComparison;
import know.engine.eval.model.EvalReportSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 两次评测批次的指标对比
 */
@Service
public class EvalCompareService {

    // 注入持久化服务，加载快照与完整报告
    @Autowired
    private EvalRunPersistenceService persistenceService;

    // 对比两次评测运行的指标差异
    public EvalComparison compare(String baselineRunId, String candidateRunId) {
        // 加载基线快照
        EvalReportSnapshot baseline = persistenceService.loadSnapshot(baselineRunId);
        // 加载候选快照
        EvalReportSnapshot candidate = persistenceService.loadSnapshot(candidateRunId);

        // 初始化对比结果
        EvalComparison comparison = new EvalComparison();
        // 写入基线 runId
        comparison.setBaselineRunId(baselineRunId);
        // 写入候选 runId
        comparison.setCandidateRunId(candidateRunId);

        // 对比检索指标
        if (baseline.getRetrieval() != null && candidate.getRetrieval() != null) {
            // Hit@K 差值
            comparison.setHitAtK(delta(baseline.getRetrieval().getHitAtK(), candidate.getRetrieval().getHitAtK()));
            // MRR 差值
            comparison.setMrr(delta(baseline.getRetrieval().getMrr(), candidate.getRetrieval().getMrr()));
            // Recall@K 差值
            comparison.setRecallAtK(delta(baseline.getRetrieval().getRecallAtK(), candidate.getRetrieval().getRecallAtK()));
            // 平均延迟差值
            comparison.setAvgLatencyMs(delta(
                    (double) baseline.getRetrieval().getAvgLatencyMs(),
                    (double) candidate.getRetrieval().getAvgLatencyMs()));
        }

        // 对比生成指标
        if (baseline.getGeneration() != null && candidate.getGeneration() != null) {
            // 忠实度差值
            comparison.setFaithfulness(delta(
                    baseline.getGeneration().getFaithfulness(),
                    candidate.getGeneration().getFaithfulness()));
            // 相关性差值
            comparison.setRelevancy(delta(
                    baseline.getGeneration().getRelevancy(),
                    candidate.getGeneration().getRelevancy()));
            // 答案正确性差值
            comparison.setAnswerCorrectness(delta(
                    baseline.getGeneration().getAnswerCorrectness(),
                    candidate.getGeneration().getAnswerCorrectness()));
        }

        // 对比上下文指标
        if (baseline.getContext() != null && candidate.getContext() != null) {
            // 上下文精确率差值
            comparison.setContextPrecision(delta(
                    baseline.getContext().getContextPrecision(),
                    candidate.getContext().getContextPrecision()));
            // 上下文召回率差值
            comparison.setContextRecall(delta(
                    baseline.getContext().getContextRecall(),
                    candidate.getContext().getContextRecall()));
        }

        // 按用例对比正确性升降
        compareCaseCorrectness(baselineRunId, candidateRunId, comparison);
        // 返回完整对比结果
        return comparison;
    }

    // 对比共同用例的答案正确性升降
    private void compareCaseCorrectness(String baselineRunId, String candidateRunId, EvalComparison comparison) {
        // 基线各用例正确性
        Map<String, Double> baselineScores = loadCorrectnessByCase(baselineRunId);
        // 候选各用例正确性
        Map<String, Double> candidateScores = loadCorrectnessByCase(candidateRunId);

        // 取两侧共同用例 ID
        Set<String> commonCaseIds = new HashSet<>(baselineScores.keySet());
        // 求交集
        commonCaseIds.retainAll(candidateScores.keySet());

        // 提升用例列表
        List<String> improved = new ArrayList<>();
        // 回退用例列表
        List<String> regressed = new ArrayList<>();
        // 逐用例比较差值
        for (String caseId : commonCaseIds) {
            // 候选减基线
            double delta = candidateScores.get(caseId) - baselineScores.get(caseId);
            // 提升阈值 0.1
            if (delta >= 0.1) {
                // 记为提升
                improved.add(caseId);
            } else if (delta <= -0.1) {
                // 下降阈值 0.1，记为回退
                regressed.add(caseId);
            }
        }
        // 写入提升列表
        comparison.setImprovedCaseIds(improved);
        // 写入回退列表
        comparison.setRegressedCaseIds(regressed);
    }

    // 加载某次运行中各用例的答案正确性分数
    private Map<String, Double> loadCorrectnessByCase(String runId) {
        // 加载完整报告
        EvalBatchReport report = persistenceService.loadFull(runId);
        // 过滤成功且有正确性分数的用例，映射为 caseId -> score
        return report.getCaseResults().stream()
                .filter(EvalCaseResult::isSuccess)
                .filter(result -> result.getGenerationScores() != null)
                .filter(result -> result.getGenerationScores().getAnswerCorrectness() != null)
                .collect(Collectors.toMap(
                        EvalCaseResult::getCaseId,
                        result -> result.getGenerationScores().getAnswerCorrectness(),
                        (left, right) -> left));
    }

    // 构造单项指标的 baseline/candidate/delta
    private EvalComparison.MetricDelta delta(Double baseline, Double candidate) {
        // 新建差值对象
        EvalComparison.MetricDelta metricDelta = new EvalComparison.MetricDelta();
        // 写入基线值
        metricDelta.setBaseline(baseline);
        // 写入候选值
        metricDelta.setCandidate(candidate);
        // 两侧均非空才算差值
        if (baseline != null && candidate != null) {
            // candidate - baseline
            metricDelta.setDelta(candidate - baseline);
        }
        // 返回差值
        return metricDelta;
    }
}
