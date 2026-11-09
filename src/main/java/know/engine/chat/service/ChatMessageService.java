package know.engine.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import know.engine.chat.entity.ChatMessage;

import java.util.List;

/** 消息数据访问。创建、检查点和终态更新由 ChatRunStore 和轮次记录一起提交。 */
public interface ChatMessageService extends IService<ChatMessage> {
    List<ChatMessage> getMessagesByConversationId(String conversationId);

    ChatMessage getByMessageId(String messageId);

    boolean deleteMessagesByConversationId(String conversationId);

    /** 按当前用户消息 ID 定位历史边界，只返回旧记录和已完成轮次的用户/助手消息。 */
    List<ChatMessage> getRecentMessagesBefore(String conversationId, String messageId, int limit);
}
