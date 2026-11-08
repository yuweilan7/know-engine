package know.engine.ai.service;

import know.engine.ai.constant.PromptResources;
import know.engine.ai.entity.IntentRecognitionResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 意图识别 AI 服务（Spring 自动装配版）
 * 在 RAG 问答链路入口调用，判断用户问题是「知识问答」还是「闲聊/通用问答」，决定后续是否触发知识库检索

 * openAiChatModel 定义在 langchain4j-open-ai-spring-boot-starter 的 AutoConfig 中，由 application.yml 的 langchain4j.open-ai.* 配置触发创建
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "openAiChatModel", chatMemoryProvider = "conversationChatMemoryProvider")
public interface IntentRecognitionService {

    /**
     * 对用户消息进行意图识别和分类
     *
     * @param conversationId 会话 ID，用于多轮上下文记忆
     * @param userMessage    用户原始问题
     * @return 结构化意图识别结果
     */
    @SystemMessage(fromResource = PromptResources.INTENT_RECOGNITION)
    IntentRecognitionResult chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
