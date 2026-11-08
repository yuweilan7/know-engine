package know.engine.ai.constant;

import know.engine.ai.prompt.PromptService;

/**
 * 提示词资源路径常量，对应 {@code src/main/resources/prompts/} 下的文本文件
 * 由 {@link PromptService} 加载，或在 {@code @SystemMessage(fromResource=...)} 中直接引用
 */
public final class PromptResources {

    private static final String PROMPT_ROOT = "prompts/";

    /** 意图识别系统提示词，用于判断用户问题是知识问答还是闲聊 */
    public static final String INTENT_RECOGNITION = PROMPT_ROOT + "intent-recognition-new-prompt.txt";

    /** 通用问答系统提示词，无 RAG 上下文时的默认回答模板 */
    public static final String GENERIC_QA = PROMPT_ROOT + "generic-qa-prompt.txt";

    /** 查询改写提示词，将用户原始问题改写为更适合检索的表述 */
    public static final String QUERY_REWRITE = PROMPT_ROOT + "query-rewrite-prompt.txt";

    /** 图片描述用户提示词，用于文档解析时对图片内容生成文字描述 */
    public static final String IMAGE_DESCRIPTION_USER = PROMPT_ROOT + "image-description-user-prompt.txt";

    /** 会话标题生成系统提示词 */
    public static final String TITLE_SUMMARY_SYSTEM = PROMPT_ROOT + "title-summary-system-prompt.txt";

    /** 会话标题生成用户提示词，携带用户首条问题作为输入 */
    public static final String TITLE_SUMMARY_USER = PROMPT_ROOT + "title-summary-user-prompt.txt";

    /** 通用闲聊系统提示词，用于 {@link know.engine.ai.service.CommonChatService} */
    public static final String COMMON_CHAT_SYSTEM = PROMPT_ROOT + "common-chat-system-prompt.txt";

    /** RAG 内容注入提示词，将检索到的知识片段拼入 LLM 上下文 */
    public static final String RAG_CONTENT_INJECTOR = PROMPT_ROOT + "rag-content-injector-prompt.txt";

    /** RAG 评测：回答忠实度（Faithfulness） */
    public static final String EVAL_FAITHFULNESS = PROMPT_ROOT + "eval-faithfulness-prompt.txt";

    /** RAG 评测：回答相关性（Answer Relevancy） */
    public static final String EVAL_RELEVANCY = PROMPT_ROOT + "eval-relevancy-prompt.txt";

    /** RAG 评测：回答正确性（Answer Correctness） */
    public static final String EVAL_CORRECTNESS = PROMPT_ROOT + "eval-correctness-prompt.txt";

    /** RAG 评测：上下文精确率（Context Precision） */
    public static final String EVAL_CONTEXT_PRECISION = PROMPT_ROOT + "eval-context-precision-prompt.txt";

    /** RAG 评测：上下文召回率（Context Recall） */
    public static final String EVAL_CONTEXT_RECALL = PROMPT_ROOT + "eval-context-recall-prompt.txt";

    private PromptResources() {
    }
}
