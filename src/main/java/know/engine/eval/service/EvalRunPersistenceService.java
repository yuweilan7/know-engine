package know.engine.eval.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import know.engine.eval.entity.EvalRun;
import know.engine.eval.entity.EvalRunCase;
import know.engine.eval.mapper.EvalRunCaseMapper;
import know.engine.eval.mapper.EvalRunMapper;
import know.engine.eval.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测结果持久化与加载
 */
@Service
@Slf4j
public class EvalRunPersistenceService {

    // 已完成状态常量
    private static final String STATUS_COMPLETED = "COMPLETED";

    // 注入批次 Mapper，eval_run 表访问
    @Autowired
    private EvalRunMapper evalRunMapper;

    // 注入用例 Mapper，eval_run_case 表访问
    @Autowired
    private EvalRunCaseMapper evalRunCaseMapper;

    // 事务内保存整份报告，将批量报告落库
    @Transactional
    public void save(EvalBatchReport report) {
        // 构造批次实体
        EvalRun evalRun = new EvalRun();
        // 业务 runId
        evalRun.setRunId(report.getRunId());
        // 数据集路径
        evalRun.setDatasetPath(report.getDatasetPath());
        // 配置序列化为 JSON
        evalRun.setConfigJson(JSON.toJSONString(report.getConfig()));
        // 摘要快照序列化为 JSON
        evalRun.setSummaryJson(JSON.toJSONString(EvalReportSnapshot.fromReport(report)));
        // 用例总数
        evalRun.setTotalCases(report.getTotalCases());
        // 检索评测数
        evalRun.setEvaluatedCases(report.getEvaluatedCases());
        // 生成评测数
        evalRun.setGenerationEvaluatedCases(report.getGenerationEvaluatedCases());
        // 标记完成
        evalRun.setStatus(STATUS_COMPLETED);
        // 插入批次行
        evalRunMapper.insert(evalRun);

        // 逐条插入用例结果
        for (EvalCaseResult caseResult : report.getCaseResults()) {
            EvalRunCase runCase = toEntity(report.getRunId(), caseResult);
            evalRunCaseMapper.insert(runCase);
        }
        log.info("评测结果已落库: runId={}, cases={}", report.getRunId(), report.getCaseResults().size());
    }

    // 加载含用例明细的完整报告
    public EvalBatchReport loadFull(String runId) {
        // 查询批次
        EvalRun evalRun = findRun(runId);
        // 构造报告外壳
        EvalBatchReport report = new EvalBatchReport();
        // 写入 runId、数据集路径
        report.setRunId(evalRun.getRunId());
        report.setDatasetPath(evalRun.getDatasetPath());

        // 解析摘要快照
        EvalReportSnapshot snapshot = JSON.parseObject(evalRun.getSummaryJson(), EvalReportSnapshot.class);
        // 快照非空则回填汇总字段
        if (snapshot != null) {
            snapshot.applyTo(report);
        }
        // 若配置仍为空则从 configJson 回填
        if (report.getConfig() == null && evalRun.getConfigJson() != null) {
            report.setConfig(JSON.parseObject(evalRun.getConfigJson(), EvalConfig.class));
        }

        // 按 runId 查询用例并按 id 升序
        List<EvalRunCase> runCases = evalRunCaseMapper.selectList(
                new LambdaQueryWrapper<EvalRunCase>()
                        .eq(EvalRunCase::getRunId, runId)
                        .orderByAsc(EvalRunCase::getId));

        List<EvalCaseResult> caseResults = new ArrayList<>();
        for (EvalRunCase runCase : runCases) {
            // 转换单条
            caseResults.add(toDto(runCase));
        }
        // 写入用例列表
        report.setCaseResults(caseResults);
        return report;
    }

    // 仅加载摘要快照
    public EvalReportSnapshot loadSnapshot(String runId) {
        // 查询批次
        EvalRun evalRun = findRun(runId);
        // 反序列化快照
        EvalReportSnapshot snapshot = JSON.parseObject(evalRun.getSummaryJson(), EvalReportSnapshot.class);
        // 快照缺 datasetPath 时用实体补齐
        if (snapshot != null && snapshot.getDatasetPath() == null) {
            // 回填路径
            snapshot.setDatasetPath(evalRun.getDatasetPath());
        }
        return snapshot;
    }

    // 分页查询批次摘要
    public Page<EvalRunSummary> listSummaries(int page, int size) {
        // 按创建时间倒序分页查实体
        Page<EvalRun> entityPage = evalRunMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<EvalRun>()
                        .orderByDesc(EvalRun::getCreatedAt));

        // 构造摘要分页壳
        Page<EvalRunSummary> summaryPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        // 收集摘要列表
        List<EvalRunSummary> summaries = new ArrayList<>();
        // 逐条转换
        for (EvalRun evalRun : entityPage.getRecords()) {
            // 实体转摘要
            summaries.add(toSummary(evalRun));
        }
        // 写入分页记录
        summaryPage.setRecords(summaries);
        return summaryPage;
    }

    // 判断 runId 是否已落库，计数大于 0 即存在
    public boolean exists(String runId) {
        return evalRunMapper.selectCount(new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getRunId, runId)) > 0;
    }

    // 按 runId 查询批次，不存在则抛异常
    private EvalRun findRun(String runId) {
        // 等值查询
        EvalRun evalRun = evalRunMapper.selectOne(new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getRunId, runId));
        // 未找到
        if (evalRun == null) {
            // 抛出业务异常
            throw new IllegalArgumentException("评测报告不存在: " + runId);
        }
        return evalRun;
    }

    // 用例结果 DTO 转持久化实体
    private EvalRunCase toEntity(String runId, EvalCaseResult caseResult) {
        // 新建实体
        EvalRunCase runCase = new EvalRunCase();
        // 所属运行 ID
        runCase.setRunId(runId);
        // 用例 ID
        runCase.setCaseId(caseResult.getCaseId());
        // 问题
        runCase.setQuestion(caseResult.getQuestion());
        // 答案
        runCase.setAnswer(caseResult.getAnswer());
        // 转换内容
        runCase.setTransformContent(caseResult.getTransformContent());
        // 检索分片 ID
        runCase.setRetrievedChunkIds(caseResult.getRetrievedChunkIds());
        // 检索文档 ID
        runCase.setRetrievedDocumentIds(caseResult.getRetrievedDocumentIds());
        // RAG 引用
        runCase.setRagReferences(caseResult.getRagReferences());
        // 检索评分
        runCase.setRetrievalScores(caseResult.getRetrievalScores());
        // 生成评分
        runCase.setGenerationScores(caseResult.getGenerationScores());
        // 耗时
        runCase.setLatencyMs(caseResult.getLatencyMs());
        // 成功标记转为 0/1
        runCase.setSuccess(caseResult.isSuccess() ? 1 : 0);
        // 错误信息
        runCase.setErrorMessage(caseResult.getError());
        return runCase;
    }

    // 持久化实体转用例结果 DTO
    private EvalCaseResult toDto(EvalRunCase runCase) {
        // 新建 DTO
        EvalCaseResult result = new EvalCaseResult();
        // 用例 ID
        result.setCaseId(runCase.getCaseId());
        // 问题
        result.setQuestion(runCase.getQuestion());
        // 答案
        result.setAnswer(runCase.getAnswer());
        // 转换内容
        result.setTransformContent(runCase.getTransformContent());
        // 检索分片 ID
        result.setRetrievedChunkIds(runCase.getRetrievedChunkIds());
        // 检索文档 ID
        result.setRetrievedDocumentIds(runCase.getRetrievedDocumentIds());
        // RAG 引用
        result.setRagReferences(runCase.getRagReferences());
        // 检索评分
        result.setRetrievalScores(runCase.getRetrievalScores());
        // 生成评分
        result.setGenerationScores(runCase.getGenerationScores());
        // 耗时
        result.setLatencyMs(runCase.getLatencyMs());
        // 0/1 转 boolean
        result.setSuccess(runCase.getSuccess() != null && runCase.getSuccess() == 1);
        // 错误信息
        result.setError(runCase.getErrorMessage());
        return result;
    }

    // 批次实体转列表摘要
    private EvalRunSummary toSummary(EvalRun evalRun) {
        // 新建摘要
        EvalRunSummary summary = new EvalRunSummary();
        // runId
        summary.setRunId(evalRun.getRunId());
        // 数据集路径
        summary.setDatasetPath(evalRun.getDatasetPath());
        // 创建时间
        summary.setCreatedAt(evalRun.getCreatedAt());
        // 用例总数（空安全）
        summary.setTotalCases(safeInt(evalRun.getTotalCases()));
        // 检索评测数
        summary.setEvaluatedCases(safeInt(evalRun.getEvaluatedCases()));
        // 生成评测数
        summary.setGenerationEvaluatedCases(safeInt(evalRun.getGenerationEvaluatedCases()));

        // 从快照补充关键指标
        EvalReportSnapshot snapshot = JSON.parseObject(evalRun.getSummaryJson(), EvalReportSnapshot.class);
        // 快照非空时回填
        if (snapshot != null) {
            // 检索 Hit@K
            if (snapshot.getRetrieval() != null) {
                // 写入 Hit@K
                summary.setHitAtK(snapshot.getRetrieval().getHitAtK());
            }
            // 生成指标
            if (snapshot.getGeneration() != null) {
                // 忠实度
                summary.setFaithfulness(snapshot.getGeneration().getFaithfulness());
                // 相关性
                summary.setRelevancy(snapshot.getGeneration().getRelevancy());
                // 答案正确性
                summary.setAnswerCorrectness(snapshot.getGeneration().getAnswerCorrectness());
            }
            // 上下文指标
            if (snapshot.getContext() != null) {
                // 精确率
                summary.setContextPrecision(snapshot.getContext().getContextPrecision());
                // 召回率
                summary.setContextRecall(snapshot.getContext().getContextRecall());
            }
            // 失败数
            summary.setFailedCount(snapshot.getFailedCaseIds() != null ? snapshot.getFailedCaseIds().size() : 0);
            // 待复核数
            summary.setReviewCount(snapshot.getReviewCaseIds() != null ? snapshot.getReviewCaseIds().size() : 0);
        }
        return summary;
    }

    // Integer 空安全转 int
    private int safeInt(Integer value) {
        // null 视为 0
        return value == null ? 0 : value;
    }
}
