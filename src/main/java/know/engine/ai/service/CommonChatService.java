package know.engine.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import know.engine.ai.constant.PromptResources;
import reactor.core.publisher.Flux;

/**
 * 通用闲聊 AI 服务（Spring 自动装配版）
 * 使用固定系统提示词 {@link PromptResources#COMMON_CHAT_SYSTEM}，不携带 RAG 上下文
 * 适用于意图识别判定为「闲聊」的对话场景

 * wiringMode = EXPLICIT 表示 LangChain4j Spring 集成会按这些字符串名称去容器里找对应 Bean
 * openAiStreamingChatModel 定义在 langchain4j-open-ai-spring-boot-starter 的 AutoConfig 中，由 application.yml 的 langchain4j.open-ai.* 配置触发创建
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, streamingChatModel = "openAiStreamingChatModel", chatMemoryProvider = "conversationChatMemoryProvider")
public interface CommonChatService {

    /**
     * 流式闲聊，按 conversationId 隔离对话记忆
     *
     * @param conversationId 会话 ID，用于多轮上下文记忆
     * @param message        用户输入
     * @return 模型输出的 token 流
     */
    @SystemMessage(fromResource = PromptResources.COMMON_CHAT_SYSTEM)
    Flux<String> streamChat(@MemoryId String conversationId, @UserMessage String message);
}