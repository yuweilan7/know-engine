package know.engine.chat.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.entity.ChatMessage;
import know.engine.chat.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 每轮从 MySQL 一次性读取最近五个完整问答对。意图识别、改写和生成共用这份不可变快照。
 * 未完成的助手内容保存在消息表供回看。模型历史只接收已完成的问答。
 */
@Component
@RequiredArgsConstructor
public class DatabaseChatMemoryStore {
    private final ChatMessageService messages;

    public List<dev.langchain4j.data.message.ChatMessage> loadHistory(String conversationId, String currentMessageId) {
        // 给旧数据里可能存在的空助手占位和截断边界留出空间。最后只交给模型最多 10 条配对消息。
        List<ChatMessage> rows = messages.getRecentMessagesBefore(conversationId, currentMessageId, 40);
        var history = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        ChatMessage user = null;
        for (ChatMessage row : rows) {
            if (row.getType() == ChatMessageType.USER) {
                user = row;
            } else if (row.getType() == ChatMessageType.ASSISTANT) {
                if (user != null && Objects.equals(user.getRunId(), row.getRunId()) && user.getContent() != null
                        && !user.getContent().isBlank() && row.getContent() != null && !row.getContent().isBlank()) {
                    history.add(UserMessage.from(user.getContent()));
                    history.add(AiMessage.from(row.getContent()));
                }
                user = null;
            }
        }
        return List.copyOf(history.subList(Math.max(0, history.size() - 10), history.size()));
    }
}
