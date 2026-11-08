package know.engine.eval.metrics;

import know.engine.ai.constant.PromptResources;
import know.engine.ai.prompt.PromptService;
import know.engine.chat.entity.ChatMessage;
import know.engine.eval.model.EvalCase;
import know.engine.eval.model.EvalCaseResult;
import know.engine.eval.model.GenerationScores;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM-as-Judge：生成质量 + 检索上下文质量（Context Precision / Recall）
 */
@Service
@Slf4j
public class LlmJudgeService {

    // 注入聊天模型，指定 OpenAI 聊天模型 Bean，用于发起 Judge 调用的模型
    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    // 注入 Prompt 服务，负责加载与填充 Prompt 模板
    @Autowired
    private PromptService promptService;

    // 注入响应解析器，解析 Judge 返回的 JSON
    @Autowired
    private EvalJudgeResponseParser responseParser;

    // 对单条用例执行全量 Judge
    public GenerationScores judge(EvalCase evalCase, EvalCaseResult caseResult) {
        // 初始化评分容器
        GenerationScores scores = new GenerationScores();
        // 推理失败则无法评分
        if (!caseResult.isSuccess()) {
            // 返回空评分
            return scores;
        }

        // 取出问题
        String question = evalCase.getQuestion();
        // 取出模型回答
        String answer = caseResult.getAnswer();
        // 格式化检索上下文
        String contexts = formatContexts(caseResult.getRagReferences());

        // 先评上下文 Precision/Recall
        judgeContextMetrics(evalCase, scores, question, contexts);

        // 回答为空则跳过回答类指标
        if (answer == null || answer.isBlank()) {
            // 记录告警
            log.warn("跳过回答类 Judge，回答为空: caseId={}", evalCase.getId());
            // 仅返回已有上下文评分
            return scores;
        }

        // 忠实度评分可能因模型输出异常失败
        try {
            // 评忠实度
            EvalJudgeResponseParser.JudgeScore faithfulness = judgeFaithfulness(question, contexts, answer);
            // 写入忠实度分数
            scores.setFaithfulness(faithfulness.getScore());
            // 写入忠实度理由
            scores.setFaithfulnessReason(faithfulness.getReason());
        } catch (Exception e) {
            // 捕获单指标失败，避免中断整条评测
            log.warn("Faithfulness 评分失败: caseId={}, error={}", evalCase.getId(), e.getMessage());
        }

        // 相关性评分独立容错
        try {
            // 评相关性
            EvalJudgeResponseParser.JudgeScore relevancy = judgeRelevancy(question, answer);
            // 写入相关性分数
            scores.setRelevancy(relevancy.getScore());
            // 写入相关性理由
            scores.setRelevancyReason(relevancy.getReason());
        } catch (Exception e) {
            log.warn("Relevancy 评分失败: caseId={}, error={}", evalCase.getId(), e.getMessage());
        }

        // 有标准答案才评正确性
        if (evalCase.hasAnswerGroundTruth()) {
            // 正确性评分独立容错
            try {
                // 评答案正确性：传入问题、标准答案、模型答案
                EvalJudgeResponseParser.JudgeScore correctness = judgeAnswerCorrectness(
                        question, evalCase.getGroundTruthAnswer(), answer);
                // 写入正确性分数
                scores.setAnswerCorrectness(correctness.getScore());
                // 写入正确性理由
                scores.setAnswerCorrectnessReason(correctness.getReason());
            } catch (Exception e) {
                log.warn("Answer Correctness 评分失败: caseId={}, error={}", evalCase.getId(), e.getMessage());
            }
        }

        // 返回聚合后的生成评分
        return scores;
    }

    // 评上下文质量（Precision / Recall）
    private void judgeContextMetrics(EvalCase evalCase, GenerationScores scores, String question, String contexts) {
        // 有上下文才评精确率
        if (!"(无检索上下文)".equals(contexts)) {
            // 精确率评分容错
            try {
                // 评 Context Precision
                EvalJudgeResponseParser.JudgeScore precision = judgeContextPrecision(question, contexts);
                // 写入精确率分数
                scores.setContextPrecision(precision.getScore());
                // 写入精确率理由
                scores.setContextPrecisionReason(precision.getReason());
            } catch (Exception e) {
                log.warn("Context Precision 评分失败: caseId={}, error={}", evalCase.getId(), e.getMessage());
            }
        }

        // 有标准答案且有上下文才评召回
        if (evalCase.hasAnswerGroundTruth() && !"(无检索上下文)".equals(contexts)) {
            // 召回率评分容错
            try {
                // 评 Context Recall：传入问题、标准答案、上下文
                EvalJudgeResponseParser.JudgeScore recall = judgeContextRecall(
                        question, evalCase.getGroundTruthAnswer(), contexts);
                // 写入召回率分数
                scores.setContextRecall(recall.getScore());
                // 写入召回率理由
                scores.setContextRecallReason(recall.getReason());
            } catch (Exception e) {
                log.warn("Context Recall 评分失败: caseId={}, error={}", evalCase.getId(), e.getMessage());
            }
        }
    }

    // 忠实度 Judge
    private EvalJudgeResponseParser.JudgeScore judgeFaithfulness(String question, String contexts, String answer) {
        // 构造 Prompt 变量
        Map<String, Object> variables = new HashMap<>();
        // 填入问题
        variables.put("question", question);
        // 填入上下文
        variables.put("contexts", contexts);
        // 填入回答
        variables.put("answer", answer);
        // 渲染忠实度模板
        Prompt prompt = promptService.getPromptTemplate(PromptResources.EVAL_FAITHFULNESS).apply(variables);
        // 调模型并解析分数
        return responseParser.parse(chatModel.chat(prompt.text()));
    }

    // 相关性 Judge
    private EvalJudgeResponseParser.JudgeScore judgeRelevancy(String question, String answer) {
        // 构造 Prompt 变量
        Map<String, Object> variables = new HashMap<>();
        // 填入问题
        variables.put("question", question);
        // 填入回答
        variables.put("answer", answer);
        // 渲染相关性模板
        Prompt prompt = promptService.getPromptTemplate(PromptResources.EVAL_RELEVANCY).apply(variables);
        // 调模型并解析分数
        return responseParser.parse(chatModel.chat(prompt.text()));
    }

    // 正确性 Judge
    private EvalJudgeResponseParser.JudgeScore judgeAnswerCorrectness(String question, String groundTruthAnswer, String answer) {
        // 构造 Prompt 变量
        Map<String, Object> variables = new HashMap<>();
        // 填入问题
        variables.put("question", question);
        // 填入标准答案
        variables.put("ground_truth_answer", groundTruthAnswer);
        // 填入模型答案
        variables.put("answer", answer);
        // 渲染正确性模板
        Prompt prompt = promptService.getPromptTemplate(PromptResources.EVAL_CORRECTNESS).apply(variables);
        // 调模型并解析分数
        return responseParser.parse(chatModel.chat(prompt.text()));
    }

    // 上下文精确率 Judge
    private EvalJudgeResponseParser.JudgeScore judgeContextPrecision(String question, String contexts) {
        // 构造 Prompt 变量
        Map<String, Object> variables = new HashMap<>();
        // 填入问题
        variables.put("question", question);
        // 填入上下文
        variables.put("contexts", contexts);
        // 渲染 Context Precision 模板
        Prompt prompt = promptService.getPromptTemplate(PromptResources.EVAL_CONTEXT_PRECISION).apply(variables);
        // 调模型并解析分数
        return responseParser.parse(chatModel.chat(prompt.text()));
    }

    // 上下文召回率 Judge
    private EvalJudgeResponseParser.JudgeScore judgeContextRecall(String question, String groundTruthAnswer, String contexts) {
        // 构造 Prompt 变量
        Map<String, Object> variables = new HashMap<>();
        // 填入问题
        variables.put("question", question);
        // 填入标准答案
        variables.put("ground_truth_answer", groundTruthAnswer);
        // 填入上下文
        variables.put("contexts", contexts);
        // 渲染 Context Recall 模板
        Prompt prompt = promptService.getPromptTemplate(PromptResources.EVAL_CONTEXT_RECALL).apply(variables);
        // 调模型并解析分数
        return responseParser.parse(chatModel.chat(prompt.text()));
    }

    // 将引用列表格式化为可读上下文
    private String formatContexts(List<ChatMessage.RagReference> references) {
        // 无引用时返回占位文案
        if (CollectionUtils.isEmpty(references)) {
            // 占位，后续逻辑可据此跳过
            return "(无检索上下文)";
        }
        // 对流式处理引用
        return references.stream()
                // 取出分片正文
                .map(ChatMessage.RagReference::getChunkContent)
                // 过滤空内容
                .filter(content -> content != null && !content.isBlank())
                // 收集后再二次处理
                .collect(Collectors.collectingAndThen(
                        // 先收集为列表
                        Collectors.toList(),
                        // 再格式化为带编号文本
                        contents -> {
                            // 过滤后仍为空
                            if (contents.isEmpty()) {
                                // 返回占位
                                return "(无检索上下文)";
                            }
                            // 拼接上下文文本
                            StringBuilder builder = new StringBuilder();
                            // 遍历每个片段
                            for (int i = 0; i < contents.size(); i++) {
                                // 写入片段编号与内容，片段间空行分隔
                                builder.append("[片段").append(i + 1).append("]\n")
                                        .append(contents.get(i))
                                        .append("\n\n");
                            }
                            // 去掉首尾空白后返回
                            return builder.toString().trim();
                        }));
    }
}
