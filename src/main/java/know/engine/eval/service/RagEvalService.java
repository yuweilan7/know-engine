package know.engine.eval.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import know.engine.eval.loader.EvalCaseLoader;
import know.engine.eval.metrics.LlmJudgeService;
import know.engine.eval.metrics.RetrievalMetricCalculator;
import know.engine.eval.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * RAG 评测编排：加载用例 → 批量推理 → 计算指标 → 生成报告
 */
@Service
@Slf4j
public class RagEvalService {

    // 注入用例加载器，从 JSONL 加载评测用例
    @Autowired
    private EvalCaseLoader evalCaseLoader;

    // 注入推理服务，单条用例 RAG 推理
    @Autowired
    private EvalInferenceService evalInferenceService;

    // 注入检索指标计算器，计算 Hit@K / MRR / Recall@K
    @Autowired
    private RetrievalMetricCalculator retrievalMetricCalculator;

    // 注入 LLM Judge，生成与上下文质量评分
    @Autowired
    private LlmJudgeService llmJudgeService;

    // 注入持久化服务，报告落库与查询
    @Autowired
    private EvalRunPersistenceService persistenceService;

    // 注入评测线程池，指定评测并发执行器，并发执行用例的线程池
    @Autowired
    @Qualifier("evalExecutor")
    private Executor evalExecutor;

    /**
     * 执行一次批量评测
     */
    public EvalBatchReport runBatch(EvalRunRequest request) throws IOException {
        // 配置为空则使用默认配置
        EvalConfig config = request.getConfig() != null ? request.getConfig() : new EvalConfig();
        // 加载数据集用例
        List<EvalCase> cases = evalCaseLoader.load(request.getDatasetPath());

        // 生成本次运行 ID
        String runId = generateRunId();
        log.info("RAG 评测开始: runId={}, dataset={}, cases={}, llmJudge={}, concurrency={}",
                runId, request.getDatasetPath(), cases.size(), config.isEnableLlmJudge(), config.getConcurrency());

        // 初始化报告
        EvalBatchReport report = new EvalBatchReport();
        // 写入 runId、数据集路径、配置、用例总数
        report.setRunId(runId);
        report.setDatasetPath(request.getDatasetPath());
        report.setConfig(config);
        report.setTotalCases(cases.size());
        report.setCreatedAt(LocalDateTime.now());

        // 执行全部用例（串行或并行）
        List<EvalCaseResult> caseResults = executeCases(cases, config);
        // 写入用例明细
        report.setCaseResults(caseResults);

        // 收集失败用例 ID
        for (EvalCaseResult caseResult : caseResults) {
            // 失败则加入失败列表
            if (!caseResult.isSuccess()) {
                // 记录失败 caseId
                report.getFailedCaseIds().add(caseResult.getCaseId());
            }
        }

        // 聚合检索/生成/上下文指标
        aggregateMetrics(report, config);
        // 持久化到数据库
        persistenceService.save(report);

        // 打印评测完成摘要
        log.info("RAG 评测完成: runId={}, hit@{}={}, faithfulness={}, relevancy={}, review={}, failed={}",
                runId, config.getTopK(),
                formatDouble(safeGet(report.getRetrieval(), EvalBatchReport.RetrievalMetricsSummary::getHitAtK)),
                formatDouble(safeGet(report.getGeneration(), EvalBatchReport.GenerationMetricsSummary::getFaithfulness)),
                formatDouble(safeGet(report.getGeneration(), EvalBatchReport.GenerationMetricsSummary::getRelevancy)),
                report.getReviewCaseIds().size(),
                report.getFailedCaseIds().size());

        // 返回完整报告
        return report;
    }

    // 查询报告（默认含用例明细）
    public EvalBatchReport getReport(String runId) {
        // 委托到带 includeCases 的重载
        return getReport(runId, true);
    }

    // 按 runId 查询报告，可选择是否包含用例
    public EvalBatchReport getReport(String runId, boolean includeCases) {
        if (!persistenceService.exists(runId)) {
            throw new IllegalArgumentException("评测报告不存在: " + runId);
        }
        // 需要明细则加载完整报告
        if (includeCases) {
            return persistenceService.loadFull(runId);
        }
        // 仅加载快照摘要
        EvalReportSnapshot snapshot = persistenceService.loadSnapshot(runId);
        EvalBatchReport report = new EvalBatchReport();
        report.setRunId(runId);
        snapshot.applyTo(report);
        report.setCaseResults(List.of());
        return report;
    }

    // 分页查询历史评测批次摘要
    public Page<EvalRunSummary> listRuns(int page, int size) {
        return persistenceService.listSummaries(page, size);
    }

    // 按并发配置执行全部用例
    private List<EvalCaseResult> executeCases(List<EvalCase> cases, EvalConfig config) {
        // 并发度 <=1 时串行执行
        if (config.getConcurrency() <= 1) {
            // 串行结果列表
            List<EvalCaseResult> results = new ArrayList<>();
            // 逐条处理
            for (EvalCase evalCase : cases) {
                // 推理并打分
                results.add(processCase(evalCase, config));
            }
            // 返回串行结果
            return results;
        }

        // 并行任务 Future 列表
        List<CompletableFuture<IndexedResult>> futures = new ArrayList<>();
        // 为每条用例提交异步任务
        for (int i = 0; i < cases.size(); i++) {
            // 捕获下标
            final int index = i;
            // 捕获用例引用
            final EvalCase evalCase = cases.get(i);
            // 提交到评测线程池
            futures.add(CompletableFuture.supplyAsync(() ->
                    new IndexedResult(index, processCase(evalCase, config)), evalExecutor));
        }

        // 等待全部完成
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        // 按原始下标排序后提取结果
        return futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(IndexedResult::index))
                .map(IndexedResult::result)
                .toList();
    }

    // 单条用例：推理 + 检索指标 +（可选）LLM Judge
    private EvalCaseResult processCase(EvalCase evalCase, EvalConfig config) {
        // 执行 RAG 推理
        EvalCaseResult caseResult = evalInferenceService.infer(evalCase);
        // 仅成功时计算指标
        if (caseResult.isSuccess()) {
            // 有检索 Ground Truth 则算检索指标
            if (evalCase.hasRetrievalGroundTruth()) {
                // 计算 Hit@K / MRR / Recall@K（子分块 chunkId 或其 parentChunkId 命中 GT 均算命中）
                RetrievalScores scores = retrievalMetricCalculator.calculate(
                        caseResult.getRagReferences(),
                        caseResult.getRetrievedDocumentIds(),
                        evalCase,
                        config.getTopK());
                // 写入检索评分
                caseResult.setRetrievalScores(scores);
            }
            // 开启 LLM Judge 则评生成质量
            if (config.isEnableLlmJudge()) {
                // 写入生成评分
                caseResult.setGenerationScores(llmJudgeService.judge(evalCase, caseResult));
            }
        }
        // 返回该用例结果
        return caseResult;
    }

    // 并行结果：保留原始下标以便排序还原
    private record IndexedResult(int index, EvalCaseResult result) {
    }

    // 聚合全量指标并写入报告
    private void aggregateMetrics(EvalBatchReport report, EvalConfig config) {
        // 检索评测有效数
        int retrievalEvaluatedCount = 0;
        // Hit 累加
        double hitSum = 0.0;
        // MRR 累加
        double mrrSum = 0.0;
        // Recall 累加
        double recallSum = 0.0;

        // 忠实度累加
        double faithfulnessSum = 0.0;
        // 忠实度样本数
        int faithfulnessCount = 0;
        // 相关性累加
        double relevancySum = 0.0;
        // 相关性样本数
        int relevancyCount = 0;
        // 正确性累加
        double correctnessSum = 0.0;
        // 正确性样本数
        int correctnessCount = 0;
        // 上下文精确率累加
        double contextPrecisionSum = 0.0;
        // 精确率样本数
        int contextPrecisionCount = 0;
        // 上下文召回率累加
        double contextRecallSum = 0.0;
        // 召回率样本数
        int contextRecallCount = 0;
        // 生成评测有效数
        int generationEvaluatedCount = 0;

        // 全部用例延迟，用于分位数
        List<Long> latencies = new ArrayList<>();
        // 待复核用例（保序去重）
        Set<String> reviewCaseIds = new LinkedHashSet<>();

        // 遍历每条用例结果
        for (EvalCaseResult caseResult : report.getCaseResults()) {
            // 收集延迟
            latencies.add(caseResult.getLatencyMs());

            // 失败用例进入复核列表后跳过指标累加
            if (!caseResult.isSuccess()) {
                reviewCaseIds.add(caseResult.getCaseId());
                continue;
            }

            // 有检索评分则累加检索指标，并按阈值筛入复核列表
            if (caseResult.getRetrievalScores() != null) {
                retrievalEvaluatedCount++;
                RetrievalScores scores = caseResult.getRetrievalScores();
                hitSum += scores.isHitAtK() ? 1.0 : 0.0;
                mrrSum += scores.getMrr();
                recallSum += scores.getRecallAtK();

                // Hit@K 未命中，或 MRR / Recall@K 低于各自阈值
                if (!scores.isHitAtK()
                        || scores.getMrr() < config.getReviewMrrThreshold()
                        || scores.getRecallAtK() < config.getReviewRecallAtKThreshold()) {
                    reviewCaseIds.add(caseResult.getCaseId());
                }
            }

            // 取出生成评分
            GenerationScores generationScores = caseResult.getGenerationScores();
            // 有生成评分则累加各子指标
            if (generationScores != null) {
                // 本条是否至少有一项生成类分数
                boolean hasGenerationScore = false;

                // 忠实度
                if (generationScores.getFaithfulness() != null) {
                    hasGenerationScore = true;
                    faithfulnessCount++;
                    faithfulnessSum += generationScores.getFaithfulness();
                    if (generationScores.getFaithfulness() < config.getReviewScoreThreshold()) {
                        reviewCaseIds.add(caseResult.getCaseId());
                    }
                }

                // 相关性
                if (generationScores.getRelevancy() != null) {
                    hasGenerationScore = true;
                    relevancyCount++;
                    relevancySum += generationScores.getRelevancy();
                    if (generationScores.getRelevancy() < config.getReviewScoreThreshold()) {
                        reviewCaseIds.add(caseResult.getCaseId());
                    }
                }

                // 答案正确性
                if (generationScores.getAnswerCorrectness() != null) {
                    hasGenerationScore = true;
                    correctnessCount++;
                    correctnessSum += generationScores.getAnswerCorrectness();
                    if (generationScores.getAnswerCorrectness() < config.getReviewScoreThreshold()) {
                        reviewCaseIds.add(caseResult.getCaseId());
                    }
                }

                // 上下文精确率
                if (generationScores.getContextPrecision() != null) {
                    hasGenerationScore = true;
                    contextPrecisionCount++;
                    contextPrecisionSum += generationScores.getContextPrecision();
                    if (generationScores.getContextPrecision() < config.getReviewScoreThreshold()) {
                        reviewCaseIds.add(caseResult.getCaseId());
                    }
                }

                // 上下文召回率
                if (generationScores.getContextRecall() != null) {
                    hasGenerationScore = true;
                    contextRecallCount++;
                    contextRecallSum += generationScores.getContextRecall();
                    if (generationScores.getContextRecall() < config.getReviewScoreThreshold()) {
                        reviewCaseIds.add(caseResult.getCaseId());
                    }
                }

                if (hasGenerationScore) {
                    generationEvaluatedCount++;
                }
            }
        }

        report.setEvaluatedCases(retrievalEvaluatedCount);
        report.setGenerationEvaluatedCases(generationEvaluatedCount);

        EvalBatchReport.RetrievalMetricsSummary retrievalSummary = new EvalBatchReport.RetrievalMetricsSummary();
        if (retrievalEvaluatedCount > 0) {
            retrievalSummary.setHitAtK(hitSum / retrievalEvaluatedCount);
            retrievalSummary.setMrr(mrrSum / retrievalEvaluatedCount);
            retrievalSummary.setRecallAtK(recallSum / retrievalEvaluatedCount);
        }
        retrievalSummary.setAvgLatencyMs(average(latencies));
        retrievalSummary.setP50LatencyMs(percentile(latencies, 50));
        retrievalSummary.setP95LatencyMs(percentile(latencies, 95));
        report.setRetrieval(retrievalSummary);

        EvalBatchReport.GenerationMetricsSummary generationSummary = new EvalBatchReport.GenerationMetricsSummary();
        generationSummary.setFaithfulnessCount(faithfulnessCount);
        generationSummary.setRelevancyCount(relevancyCount);
        generationSummary.setAnswerCorrectnessCount(correctnessCount);
        if (faithfulnessCount > 0) {
            generationSummary.setFaithfulness(faithfulnessSum / faithfulnessCount);
        }
        if (relevancyCount > 0) {
            generationSummary.setRelevancy(relevancySum / relevancyCount);
        }
        if (correctnessCount > 0) {
            generationSummary.setAnswerCorrectness(correctnessSum / correctnessCount);
        }
        report.setGeneration(generationSummary);

        EvalBatchReport.ContextMetricsSummary contextSummary = new EvalBatchReport.ContextMetricsSummary();
        contextSummary.setContextPrecisionCount(contextPrecisionCount);
        contextSummary.setContextRecallCount(contextRecallCount);
        if (contextPrecisionCount > 0) {
            contextSummary.setContextPrecision(contextPrecisionSum / contextPrecisionCount);
        }
        if (contextRecallCount > 0) {
            contextSummary.setContextRecall(contextRecallSum / contextRecallCount);
        }
        report.setContext(contextSummary);
        report.setReviewCaseIds(new ArrayList<>(reviewCaseIds));
    }

    // 计算平均延迟（四舍五入为 long）
    private long average(List<Long> values) {
        // 空列表返回 0
        if (values.isEmpty()) {
            // 无数据
            return 0L;
        }
        // 求平均并四舍五入
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0.0));
    }

    // 计算指定百分位延迟
    private long percentile(List<Long> values, int percentile) {
        // 空列表返回 0
        if (values.isEmpty()) {
            // 无数据
            return 0L;
        }
        // 升序排序
        List<Long> sorted = values.stream().sorted().toList();
        // 计算百分位下标（ceil 后减 1）
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        // 下标钳制到合法区间
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        // 返回对应延迟
        return sorted.get(index);
    }

    // 生成 runId：时间戳 + UUID 前 8 位
    private String generateRunId() {
        // 格式化当前时间
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        // 拼接短 UUID
        return timestamp + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // 将 double 格式化为 4 位小数（日志用）
    private String formatDouble(double value) {
        // 固定 Locale.ROOT
        return String.format(Locale.ROOT, "%.4f", value);
    }

    // 安全读取检索汇总字段，空汇总返回 0
    private double safeGet(EvalBatchReport.RetrievalMetricsSummary summary,
                           java.util.function.ToDoubleFunction<EvalBatchReport.RetrievalMetricsSummary> getter) {
        // 空则 0，否则取字段
        return summary == null ? 0.0 : getter.applyAsDouble(summary);
    }

    // 安全读取生成汇总字段，空汇总返回 0
    private double safeGet(EvalBatchReport.GenerationMetricsSummary summary,
                           java.util.function.ToDoubleFunction<EvalBatchReport.GenerationMetricsSummary> getter) {
        // 空则 0，否则取字段
        return summary == null ? 0.0 : getter.applyAsDouble(summary);
    }
}