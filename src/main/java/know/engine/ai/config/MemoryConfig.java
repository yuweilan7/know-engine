package know.engine.ai.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import know.engine.chat.memory.DatabaseChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 对话记忆配置
 * 全链路统一使用 {@link #conversationChatMemoryProvider(DatabaseChatMemoryStore)}：
 * 意图识别、问答(闲聊、RAG) 均以 {@code conversationId} 为记忆标识，经 Redis 缓存 + DB 回源读取历史
 */
@Configuration
public class MemoryConfig {

    /**
     * 会话级 ChatMemoryProvider，供意图识别、问答(闲聊、RAG) 共用
     * {@code memoryId} 统一为 {@code conversationId}，读写策略见 {@link DatabaseChatMemoryStore}
     */
    @Bean
    public ChatMemoryProvider conversationChatMemoryProvider(DatabaseChatMemoryStore store) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();
    }
}
