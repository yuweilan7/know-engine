package know.engine.chat.service.impl;

import know.engine.chat.entity.ChatConversation;
import know.engine.chat.mapper.ChatConversationMapper;
import know.engine.chat.service.ChatConversationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 会话表 Service 实现类
 */
@Service
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation> implements ChatConversationService {

    @Override
    public List<ChatConversation> getConversationsByUserId(String userId) {
        return this.list(new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getUserId, userId)
                .orderByDesc(ChatConversation::getUpdatedAt));
    }

    @Override
    public String createConversation(String userId, String title) {
        String conversationId = UUID.randomUUID().toString().replace("-", "") + userId;

        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId(conversationId);
        conversation.setUserId(userId);
        conversation.setTitle(title != null ? title : "新对话");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        this.save(conversation);
        return conversationId;
    }

    @Override
    public boolean updateTitle(String conversationId, String title) {
        return this.update(new LambdaUpdateWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId)
                .set(ChatConversation::getTitle, title)
                .set(ChatConversation::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public boolean deleteConversation(String conversationId) {
        return this.remove(new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId));
    }
}
