package know.engine.rag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Rewrite uses the round's existing history snapshot. Persistence belongs to the chat flow. */
public record KnowEngineQueryTransformer(ChatModel chatModel, PromptTemplate promptTemplate) {
    public String rewrite(String query, List<ChatMessage> history) {
        String chatMemory = history.stream()
                .map(message -> switch (message) {
                    case UserMessage user -> "User: " + user.singleText();
                    case AiMessage ai -> "AI: " + ai.text();
                    default -> "";
                })
                .collect(Collectors.joining("\n"));
        return chatModel.chat(promptTemplate.apply(Map.of("query", query, "chatMemory", chatMemory)).text());
    }
}
