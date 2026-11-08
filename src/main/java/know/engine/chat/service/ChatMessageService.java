package know.engine.chat.service;

import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.entity.ChatMessage;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 消息表 Service 接口
 */
public interface ChatMessageService extends IService<ChatMessage> {

    /**
     * 根据会话ID获取消息列表
     *
     * @param conversationId 会话ID
     * @return 消息列表
     */
    List<ChatMessage> getMessagesByConversationId(String conversationId);

    /**
     * 根据消息ID获取消息
     *
     * @param messageId 消息ID
     * @return 消息信息
     */
    ChatMessage getByMessageId(String messageId);

    /**
     * 保存对话消息
     *
     * @param conversationId 会话ID
     * @param type           消息类型
     * @param content        消息内容；助手消息可先传 null，流式完成后再 updateContent
     * @return 消息ID
     */
    String saveMessage(String conversationId, ChatMessageType type, String content);

    /**
     * 更新问题改写结果（transformContent）
     *
     * @param messageId        消息ID
     * @param transformContent 改写后的问题
     */
    void updateTransformContent(String messageId, String transformContent);

    /**
     * 更新RAG引用内容
      *
      * @param messageId        消息ID
      * @param ragReferences    RAG引用内容
     */
    void updateRagReferences(String messageId, List<ChatMessage.RagReference> ragReferences);

    /**
     * 更新助手消息内容与模型名称
     *
     * @param messageId 消息ID
     * @param content   消息内容
     * @param modelName 生成该条助手消息时使用的模型名称
     */
    void updateContent(String messageId, String content, String modelName);

    /**
     * 删除会话的所有消息
     *
     * @param conversationId 会话ID
     * @return 是否成功
     */
    boolean deleteMessagesByConversationId(String conversationId);

    /**
     * 获取会话最近N条消息
     *
     * @param conversationId 会话ID
     * @param limit          消息数量
     * @return 消息列表
     */
    List<ChatMessage> getRecentMessages(String conversationId, int limit);
}
