package know.engine.ai.entity;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 意图识别结构化输出，由 {@link know.engine.ai.service.IntentRecognitionService} 返回
 * LLM 以 JSON 形式输出，LangChain4j 自动反序列化为此 record
 * {@code related} 为 true 时走 RAG 检索链路，false 时走通用闲聊或直答
 */
public record IntentRecognitionResult(
        /** LLM 对意图判断的推理过程，便于调试与审计 */
        @JsonPropertyDescription("意图识别的推断理由")
        String reasoning,

        /** 是否需要检索知识库：true=知识问答，false=闲聊或无需检索 */
        @JsonPropertyDescription("用户问题是否需要检索知识库：true-知识问答，false-闲聊或不需要检索")
        boolean related,

        /** 意图分类标签，如「知识问答」「闲聊与通用问答」「其他」 */
        @JsonPropertyDescription("意图识别结果,知识问答、闲聊与通用问答、其他")
        String intent) {
}
