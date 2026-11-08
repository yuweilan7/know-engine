package know.engine.chat.constant;

/**
 * RAG 检索来源类型，标记引用片段是通过哪种检索策略命中的
 * 写入 {@link know.engine.chat.entity.ChatMessage.RagReference#retrievalSource}，
 * 由 {@link know.engine.rag.util.RetrievalSourceUtil} 在 RAG 管道中自动解析
 */
public enum RetrievalSource {

    /** 向量（KNN 语义）检索命中 */
    VECTOR,

    /** 全文（关键词 match）检索命中 */
    KEYWORD,

    /** 向量与全文双路均命中，经 RRF 融合 */
    HYBRID,

    /** 无法追溯检索来源、仅经 Rerank 处理的片段（如测试接口） */
    RERANK
}
