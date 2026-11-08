package know.engine.eval.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 持久化到 eval_run.summary_json 的快照（不含 caseResults 明细）
 */
@Data
public class EvalReportSnapshot {

    // 本次评测配置
    private EvalConfig config;

    // 用例总数
    private int totalCases;

    // 已完成检索评测的用例数
    private int evaluatedCases;

    // 已完成生成层评测的用例数
    private int generationEvaluatedCases;

    // 报告创建时间
    private LocalDateTime createdAt;

    // 数据集路径
    private String datasetPath;

    // 检索指标汇总
    private EvalBatchReport.RetrievalMetricsSummary retrieval;

    // 生成指标汇总
    private EvalBatchReport.GenerationMetricsSummary generation;

    // 上下文质量指标汇总
    private EvalBatchReport.ContextMetricsSummary context;

    // 失败用例 ID 列表
    private List<String> failedCaseIds = new ArrayList<>();

    // 建议人工复核的用例 ID
    private List<String> reviewCaseIds = new ArrayList<>();

    // 从完整报告裁剪出快照（不含 caseResults）
    public static EvalReportSnapshot fromReport(EvalBatchReport report) {
        // 新建快照对象
        EvalReportSnapshot snapshot = new EvalReportSnapshot();
        // 拷贝配置
        snapshot.setConfig(report.getConfig());
        // 拷贝总用例数
        snapshot.setTotalCases(report.getTotalCases());
        // 拷贝检索评测数
        snapshot.setEvaluatedCases(report.getEvaluatedCases());
        // 拷贝生成评测数
        snapshot.setGenerationEvaluatedCases(report.getGenerationEvaluatedCases());
        // 拷贝创建时间
        snapshot.setCreatedAt(report.getCreatedAt());
        // 拷贝数据集路径
        snapshot.setDatasetPath(report.getDatasetPath());
        // 拷贝检索汇总
        snapshot.setRetrieval(report.getRetrieval());
        // 拷贝生成汇总
        snapshot.setGeneration(report.getGeneration());
        // 拷贝上下文汇总
        snapshot.setContext(report.getContext());
        // 拷贝失败用例
        snapshot.setFailedCaseIds(report.getFailedCaseIds());
        // 拷贝复核用例
        snapshot.setReviewCaseIds(report.getReviewCaseIds());
        // 返回快照
        return snapshot;
    }

    // 将快照字段回填到报告对象
    public void applyTo(EvalBatchReport report) {
        // 回填配置
        report.setConfig(config);
        // 回填总用例数
        report.setTotalCases(totalCases);
        // 回填检索评测数
        report.setEvaluatedCases(evaluatedCases);
        // 回填生成评测数
        report.setGenerationEvaluatedCases(generationEvaluatedCases);
        // 回填创建时间
        report.setCreatedAt(createdAt);
        // 回填数据集路径
        report.setDatasetPath(datasetPath);
        // 回填检索汇总
        report.setRetrieval(retrieval);
        // 回填生成汇总
        report.setGeneration(generation);
        // 回填上下文汇总
        report.setContext(context);
        // 回填失败用例
        report.setFailedCaseIds(failedCaseIds);
        // 回填复核用例
        report.setReviewCaseIds(reviewCaseIds);
    }
}
