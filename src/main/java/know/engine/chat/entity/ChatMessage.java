package know.engine.chat.entity;

import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.constant.RetrievalSource;
import know.engine.document.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.*;

import java.util.List;

/**
 * 消息表对应实体
 */
@Getter
@Setter
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage extends BaseEntity {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 消息唯一标识
     */
    private String messageId;

    /**
     * 所属会话ID
     */
    private String conversationId;

    private String runId;

    /**
     * 角色：user/assistant
     */
    private ChatMessageType type;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 改写后的内容
     */
    private String transformContent;

    /**
     * Token数量
     */
    private Integer tokenCount;

    /**
     * 使用的模型名称
     */
    private String modelName;

    /**
     * RAG引用内容,JSON数组
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<RagReference> ragReferences;

    /**
     * RAG引用内容内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RagReference {
        /**
         * 文档ID
         */
        private String documentId;

        /** 本次问答实际命中的版本记录 ID。早期聊天记录可能没有此字段。 */
        private String versionId;

        /**
         * 文档URL
         */
        private String url;

        /**
         * 文档标题
         */
        private String documentTitle;

        /**
         * 文档块ID
         */
        private String chunkId;

        /**
         * 父分块ID（子分块检索命中时携带，用于评测与父块回溯）
         */
        private String parentChunkId;

        /**
         * 文档块内容
         */
        private String chunkContent;

        /**
         * 历史兼容的原始分数字段。新轮次分别记录 keywordScore（BM25）和 vectorScore（ES KNN _score）。
         * 双路都命中时此字段沿用关键词原始分，融合排名请读 rrfScore。
         */
        private Double similarityScore;

        /**
         * 重排序分数（Reranker 模型对 query 与 chunk 的相关性评分，仅经重排阶段时有值）, 最终依据
         */
        private Double rerankScore;

        /** 指向实际注入的上下文项。同一个父块的兄弟命中可以关联到同一个 contextId。 */
        private String contextId;

        /** 每个上下文项的首条引用保存注入文本，其余引用通过 contextId 关联，避免重复保存整段父块。 */
        private String contextContent;

        /** 各路贡献按名次累加所得的分数。关键词和向量原分保存在各自字段。 */
        private Double rrfScore;
        private Integer keywordRank;
        private Double keywordScore;
        private Integer vectorRank;
        private Double vectorScore;

        /**
         * 检索来源：vector/keyword/hybrid/rerank
         */
        private RetrievalSource retrievalSource;

    }
}
