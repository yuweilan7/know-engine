package know.engine.eval.model;

import know.engine.chat.entity.ChatMessage;
import lombok.Data;

import java.util.List;

/**
 * 单条用例的推理与评分结果
 */
@Data
public class EvalCaseResult {

    // 对应用例 ID
    private String caseId;

    // 提问文本
    private String question;

    // 模型生成答案
    private String answer;

    // 中间转换/改写内容
    private String transformContent;

    // 检索到的分片 ID
    private List<String> retrievedChunkIds;

    // 检索到的文档 ID
    private List<String> retrievedDocumentIds;

    // RAG 引用明细
    private List<ChatMessage.RagReference> ragReferences;

    // 检索层评分
    private RetrievalScores retrievalScores;

    // 生成层评分
    private GenerationScores generationScores;

    // 推理耗时（毫秒）
    private long latencyMs;

    // 本条是否执行成功
    private boolean success;

    // 失败时的错误信息
    private String error;
}
