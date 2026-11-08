package know.engine.eval.model;

import jodd.util.StringUtil;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * RAG 评测单条测试用例（对应 JSONL 一行）
 */
@Data
public class EvalCase {

    // 用例唯一标识
    private String id;

    // 用户提问文本
    private String question;

    // 标准答案（生成层评测用）
    private String groundTruthAnswer;

    // 标准相关分片 ID 列表
    private List<String> groundTruthChunkIds;

    // 标准相关文档 ID 列表
    private List<String> groundTruthDocumentIds;

    // 判断是否具备检索层 Ground Truth : chunk 或 document 任一标注非空即视为有检索真值
    public boolean hasRetrievalGroundTruth() {
        return CollectionUtils.isNotEmpty(groundTruthChunkIds) || CollectionUtils.isNotEmpty(groundTruthDocumentIds);
    }

    // 判断是否具备答案层 Ground Truth : 标准答案存在且非空白
    public boolean hasAnswerGroundTruth() {
        return StringUtil.isNotBlank(groundTruthAnswer);
    }
}
