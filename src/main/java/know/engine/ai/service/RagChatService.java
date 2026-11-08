package know.engine.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 知识引擎主对话 AI 服务（动态构建版）
 * 与 {@link CommonChatService} 不同，此接口不带固定 {@code @SystemMessage}，
 * 由 {@code ChatApplicationService} 在运行时通过 {@code AiServices.builder()} 注入

 * 动态 system prompt（含 RAG 检索上下文或通用 QA 模板）及请求级 {@code RetrievalAugmentor}，

 * ragChatModel 在 AiModelConfig 中定义
 */
public interface RagChatService {

    /**
     * 流式对话，system prompt 由调用方在构建 AiService 时动态指定
     *
     * @param conversationId 会话 ID，用于记忆隔离与消息持久化
     * @param message        用户输入（已改写后的查询文本）
     * @return 模型输出的 token 流
     */
    Flux<String> streamChat(@MemoryId String conversationId, @UserMessage String message);
}
