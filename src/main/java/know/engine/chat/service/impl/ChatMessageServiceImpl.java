package know.engine.chat.service.impl;

import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.entity.ChatMessage;
import know.engine.chat.mapper.ChatMessageMapper;
import know.engine.chat.service.ChatMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 消息表 Service 实现类
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    @Override
    public List<ChatMessage> getMessagesByConversationId(String conversationId) {
        return this.list(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }

    @Override
    public ChatMessage getByMessageId(String messageId) {
        return this.getOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    /**
     * 保存数据 消息表 chat_message
     */
    @Override
    public String saveMessage(String conversationId, ChatMessageType type, String content) {
        String messageId = UUID.randomUUID().toString().replace("-", "");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setType(type);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());

        this.save(message);
        return messageId;
    }

    @Override
    public void updateTransformContent(String messageId, String transformContent) {
        ChatMessage update = new ChatMessage();
        update.setTransformContent(transformContent);
        this.update(update, new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    @Override
    public void updateRagReferences(String messageId, List<ChatMessage.RagReference> ragReferences) {
        ChatMessage update = new ChatMessage();
        update.setRagReferences(ragReferences);
        this.update(update, new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    @Override
    public void updateContent(String messageId, String content, String modelName) {
        ChatMessage update = new ChatMessage();
        update.setContent(content);
        update.setModelName(modelName);
        this.update(update, new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    @Override
    public boolean deleteMessagesByConversationId(String conversationId) {
        return this.remove(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));
    }

    @Override
    public List<ChatMessage> getRecentMessages(String conversationId, int limit) {
        // 查询最新的 limit+2 条，排除最新的2条（当前轮次刚保存的user消息和空assistant消息）
        Page<ChatMessage> page = this.page(
                new Page<>(1, limit + 2),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByDesc(ChatMessage::getCreatedAt)
        );

        List<ChatMessage> records = page.getRecords();
        // 去掉最新的2条
        if (records.size() > 2) {
            records = records.subList(2, records.size());
        } else {
            return new java.util.ArrayList<>();
        }

        // 返回列表需要反转，使其按时间正序排列
        java.util.Collections.reverse(records);
        return records;
    }
}
