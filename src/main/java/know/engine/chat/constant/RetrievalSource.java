package know.engine.chat.constant;

/**
 * RAG 检索来源类型，标记引用片段是通过哪种检索策略命中的
 * 写入 {@link know.engine.chat.entity.ChatMessage.RagReference#retrievalSource}
 */
public enum RetrievalSource {

    /** 向量（KNN 语义）检索命中 */
    VECTOR,

    /** 全文（关键词 match）检索命中 */
    KEYWORD,

    /** 向量与全文双路均命中，经 RRF 融合 */
    HYBRID,

    /** 历史引用 JSON 兼容值。新轮次保留原始检索来源。 */
    RERANK
}
