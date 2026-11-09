package know.engine.rag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 使用 UTF-8 字节数估算应用侧提示词大小，包含消息结构开销和配置的输出、安全预留。
 * 这个预算独立于模型上下文窗口和分词器，便于在更换模型或遇到中英文混合内容时限制请求大小。
 */
public final class PromptBudget {
    private static final int MESSAGE_OVERHEAD_BYTES = 64;

    private PromptBudget() {}

    public static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    public record Fit(List<ChatMessage> history, int usedBytes, int availableBytes) {}

    public static Fit fit(int inputLimit, String system, String user, List<ChatMessage> history) {
        int used = bytes(system) + bytes(user) + 2 * MESSAGE_OVERHEAD_BYTES;
        if (used > inputLimit) throw new IllegalArgumentException("Question exceeds the configured prompt budget");
        int from = history.size();
        // 从最近问答向前按对保留，最终返回正序历史。预算不足时整对裁掉，避免孤立的问答片段。
        while (from >= 2) {
            var question = history.get(from - 2);
            var answer = history.get(from - 1);
            if (!(question instanceof UserMessage previous) || !(answer instanceof AiMessage reply)) break;
            int cost = bytes(previous.singleText()) + bytes(reply.text()) + 2 * MESSAGE_OVERHEAD_BYTES;
            if (cost > inputLimit - used) break;
            used += cost;
            from -= 2;
        }
        return new Fit(List.copyOf(history.subList(from, history.size())), used, inputLimit - used);
    }
}
