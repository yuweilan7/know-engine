package know.engine.ai.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import know.engine.ai.constant.PromptResources;

/**
 * 会话标题自动生成 AI 服务（Spring 自动装配版）
 * 用户发起新对话时，根据首条问题生成简短标题，写入 {@code chat_conversation.title}

 * titleChatModel 在 AiModelConfig 中定义
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "titleChatModel")
public interface TitleSummaryService {

    /**
     * 根据用户首条问题生成会话标题
     *
     * @param userQuestion 用户首条消息内容
     * @return 生成的标题文本
     */
    @SystemMessage(fromResource = PromptResources.TITLE_SUMMARY_SYSTEM)
    @UserMessage(fromResource = PromptResources.TITLE_SUMMARY_USER)
    String generateTitle(String userQuestion);
}
