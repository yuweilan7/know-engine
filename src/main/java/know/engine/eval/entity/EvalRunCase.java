package know.engine.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import know.engine.chat.entity.ChatMessage;
import know.engine.document.entity.BaseEntity;
import know.engine.eval.model.GenerationScores;
import know.engine.eval.model.RetrievalScores;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * RAG 评测单条用例结果表
 */
@Getter
@Setter
@TableName(value = "eval_run_case", autoResultMap = true)
public class EvalRunCase extends BaseEntity {

    // 主键自增，数据库主键
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    // 所属评测运行 ID
    private String runId;

    // 用例业务 ID
    private String caseId;

    // 提问文本
    private String question;

    // 模型答案
    private String answer;

    // 中间转换内容
    private String transformContent;

    // 列表字段按 JSON 存取，检索分片 ID 列表
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> retrievedChunkIds;

    // 列表字段按 JSON 存取，检索文档 ID 列表
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> retrievedDocumentIds;

    // 对象列表按 JSON 存取，RAG 引用明细
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ChatMessage.RagReference> ragReferences;

    // 复杂对象按 JSON 存取，检索评分
    @TableField(typeHandler = JacksonTypeHandler.class)
    private RetrievalScores retrievalScores;

    // 复杂对象按 JSON 存取，生成评分
    @TableField(typeHandler = JacksonTypeHandler.class)
    private GenerationScores generationScores;

    // 耗时毫秒
    private Long latencyMs;

    // 成功标记（常用 0/1）
    private Integer success;

    // 错误信息
    private String errorMessage;
}
