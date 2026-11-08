package know.engine.eval.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import know.engine.common.Result;
import know.engine.eval.loader.EvalCaseLoader;
import know.engine.eval.model.EvalBatchReport;
import know.engine.eval.model.EvalComparison;
import know.engine.eval.model.EvalDatasetUploadResponse;
import know.engine.eval.model.EvalRunRequest;
import know.engine.eval.model.EvalRunSummary;
import know.engine.eval.service.EvalCompareService;
import know.engine.eval.service.EvalExportService;
import know.engine.eval.service.RagEvalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * RAG 评测 API
 */
@RestController
@RequestMapping("/eval")
@Slf4j
public class RagEvalController {

    private static final String UPLOAD_DIR_NAME = "know-engine-eval";

    @Autowired
    private RagEvalService ragEvalService;

    @Autowired
    private EvalCompareService evalCompareService;

    @Autowired
    private EvalExportService evalExportService;

    @Autowired
    private EvalCaseLoader evalCaseLoader;

    /**
     * 执行批量评测并返回报告
     */
    @PostMapping("/run")
    public Result<EvalBatchReport> run(@RequestBody(required = false) EvalRunRequest request) {
        try {
            // 请求体为空时使用默认请求
            EvalRunRequest effectiveRequest = request != null ? request : new EvalRunRequest();
            // 执行批量评测
            EvalBatchReport report = ragEvalService.runBatch(effectiveRequest);
            return Result.ok(report, "评测完成");
        } catch (Exception e) {
            log.error("评测执行失败", e);
            return Result.fail("评测执行失败: " + e.getMessage());
        }
    }

    /**
     * 上传评测集 JSONL，落盘后返回绝对路径供 /eval/run 使用
     */
    @PostMapping("/dataset/upload")
    public Result<EvalDatasetUploadResponse> uploadDataset(@RequestParam("file") MultipartFile file) {
        Path savedPath = null;
        try {
            if (file == null || file.isEmpty()) {
                return Result.fail("请上传非空的 .jsonl 文件");
            }
            String originalName = file.getOriginalFilename();
            if (!StringUtils.hasText(originalName)
                    || !originalName.toLowerCase(Locale.ROOT).endsWith(".jsonl")) {
                return Result.fail("仅支持 .jsonl 评测集文件");
            }

            // 落盘到临时目录，文件名加 UUID 避免冲突
            Path uploadDir = Path.of(System.getProperty("java.io.tmpdir"), UPLOAD_DIR_NAME);
            Files.createDirectories(uploadDir);
            String safeName = Path.of(originalName).getFileName().toString();
            savedPath = uploadDir.resolve(UUID.randomUUID() + "-" + safeName);
            file.transferTo(savedPath);

            // 试解析，校验 JSONL 格式与必填字段
            String absolutePath = savedPath.toAbsolutePath().toString();
            evalCaseLoader.load(absolutePath);
            return Result.ok(new EvalDatasetUploadResponse(absolutePath), "上传成功");
        } catch (Exception e) {
            log.error("评测集上传失败", e);
            if (savedPath != null) {
                try {
                    Files.deleteIfExists(savedPath);
                } catch (IOException ignored) {
                    // 清理失败不影响错误返回
                }
            }
            return Result.fail("评测集上传失败: " + e.getMessage());
        }
    }

    /**
     * 按 runId 查询评测报告，可选择是否包含用例明细
     */
    @GetMapping("/report/{runId}")
    public Result<EvalBatchReport> getReport(@PathVariable String runId, @RequestParam(defaultValue = "true") boolean includeCases) {
        try {
            return Result.ok(ragEvalService.getReport(runId, includeCases));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 评测批次列表（轻量摘要）
     */
    @GetMapping("/runs")
    public Result<Page<EvalRunSummary>> listRuns(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        // 查询分页摘要
        Page<EvalRunSummary> summaries = ragEvalService.listRuns(page, size);
        return Result.ok(summaries, (int) summaries.getTotal());
    }

    /**
     * 对比两次评测批次
     */
    @GetMapping("/compare")
    public Result<EvalComparison> compare(@RequestParam String baseline,
                                          @RequestParam String candidate) {
        try {
            return Result.ok(evalCompareService.compare(baseline, candidate));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 导出 CSV 明细
     */
    @GetMapping("/report/{runId}/export.csv")
    public void exportCsv(@PathVariable String runId, HttpServletResponse response) throws IOException {
        // 加载含用例明细的完整报告
        EvalBatchReport report = ragEvalService.getReport(runId, true);
        // 转为 CSV 文本
        String csv = evalExportService.toCsv(report);

        // 设置响应编码
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 设置 CSV Content-Type
        response.setContentType("text/csv;charset=UTF-8");
        // 设置下载文件名
        response.setHeader("Content-Disposition", "attachment; filename=\"eval-" + runId + ".csv\"");
        // 写入 UTF-8 BOM，便于 Excel 识别
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        // 写入 CSV 正文
        response.getOutputStream().write(csv.getBytes(StandardCharsets.UTF_8));
    }
}