package know.engine.ai.service;

import know.engine.ai.constant.PromptResources;
import know.engine.ai.entity.IntentRecognitionResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import know.engine.chat.service.ChatService;

/**
 * AUTO 模式的意图识别契约。判断用户问题是否需要查询知识库。
 * {@link ChatService} 按轮调用 AiServices.builder，显式传入 onlineChatModel 和根据本轮历史建立的临时记忆。
 * 每个实例只持有本轮的默认记忆，分类结果不会进入用户可见的对话历史。
 */
public interface IntentRecognitionService {

    /**
     * 对用户消息进行意图识别和分类
     *
     * @param userMessage    用户原始问题
     * @return 结构化意图识别结果
     */
    @SystemMessage(fromResource = PromptResources.INTENT_RECOGNITION)
    IntentRecognitionResult chat(@UserMessage String userMessage);
}
