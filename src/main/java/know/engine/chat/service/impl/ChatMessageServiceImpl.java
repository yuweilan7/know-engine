package know.engine.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.entity.ChatMessage;
import know.engine.chat.mapper.ChatMessageMapper;
import know.engine.chat.service.ChatMessageService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {
    @Override
    public List<ChatMessage> getMessagesByConversationId(String conversationId) {
        return this.list(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getId));
    }

    @Override
    public ChatMessage getByMessageId(String messageId) {
        return this.getOne(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getMessageId, messageId));
    }

    @Override
    public boolean deleteMessagesByConversationId(String conversationId) {
        return this.remove(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, conversationId));
    }

    @Override
    public List<ChatMessage> getRecentMessagesBefore(String conversationId, String messageId, int limit) {
        // 从当前轮的用户消息往前读。旧轮次的空助手占位也要传给配对逻辑，避免问题串到下一轮。
        var page = this.page(new Page<>(1, limit, false), new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .apply("id < (select id from chat_message where conversation_id = {0} and message_id = {1})",
                        conversationId, messageId)
                .in(ChatMessage::getType, ChatMessageType.USER, ChatMessageType.ASSISTANT)
                .apply("(run_id IS NULL OR run_id IN (SELECT run_id FROM chat_run WHERE status = 'COMPLETED' AND deleted = 0))")
                .orderByDesc(ChatMessage::getId));
        return page.getRecords().reversed();
    }
}
