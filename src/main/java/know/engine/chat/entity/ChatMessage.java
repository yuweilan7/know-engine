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
         * 相似度分数= Elasticsearch 检索阶段对该 chunk 的原始相关度分数
         * 不同检索模式来源不同：
         *      向量（KNN）检索：来自 EmbeddingSearchResult 的 m.score(), 即 query embedding 与 chunk embedding 的语义相似度（LangChain4j ES KNN 返回）
         *      全文检索：来自 ES hit 的 hit.score()（match 相关度，如 BM25）, 即 ES 关键词 match 相关度（非向量余弦相似度）
         *      混合检索：同样走 mapResultsToContentList，用 ES 返回的 score, 即 ES 混合查询返回的 score
         */
        private Double similarityScore;

        /**
         * 重排序分数（Reranker 模型对 query 与 chunk 的相关性评分，仅经重排阶段时有值）, 最终依据
         */
        private Double rerankScore;

        /**
         * 检索来源：vector/keyword/hybrid/rerank
         */
        private RetrievalSource retrievalSource;

    }
}