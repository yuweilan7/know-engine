package know.engine.rag;

import know.engine.chat.service.ChatMessageService;
import com.google.common.base.Stopwatch;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.joining;

/**
 * KnowEngine 查询改写器
 * 基于 LLM 对用户查询进行智能改写优化，结合历史对话上下文，提升 RAG 检索效果
 * 处理流程：
 *      通过 progressCallback 发送进度事件通知前端"正在优化您的问题"
 *      从 Query metadata 中提取历史对话记忆，格式化为 Prompt 上下文
 *      使用 LLM 根据 Prompt 模板改写用户查询
 *      构造增强查询（附加用户ID、当前时间等上下文信息）
 *      通过 Java 21 虚拟线程异步回写改写结果到数据库（chat_message.transform_content）
 *      返回改写后的单个查询
 */
@Slf4j
public class KnowEngineQueryTransformer implements QueryTransformer {

    protected final ChatModel chatModel;

    protected final PromptTemplate promptTemplate;

    /**
     * assistant 消息的 messageId，用于回写改写结果
     */
    private final String chatMessageId;

    /**
     * 进度回调，用于流式返回前端进度信息
     */
    private final Consumer<String> progressCallback;

    /**
     * Spring 容器，由使用方在构造时传入
     */
    private static volatile ApplicationContext applicationContext;

    /**
     * 注册全局 ApplicationContext（由 SpringContextHolder 调用一次即可）
     */
    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    private ChatMessageService getChatMessageService() {
        if (applicationContext == null) {
            return null;
        }
        try {
            return applicationContext.getBean(ChatMessageService.class);
        } catch (Exception e) {
            log.warn("获取 ChatMessageService 失败", e);
            return null;
        }
    }

    public KnowEngineQueryTransformer(ChatModel chatModel, PromptTemplate promptTemplate, String chatMessageId, Consumer<String> progressCallback) {
        this.promptTemplate = ensureNotNull(promptTemplate, "promptTemplate");
        this.chatModel = ensureNotNull(chatModel, "chatModel");
        this.chatMessageId = chatMessageId;
        this.progressCallback = progressCallback;
    }

    @Override
    public Collection<Query> transform(Query query) {
        // 发送进度：开始问题改写
        if (progressCallback != null) {
            progressCallback.accept("[PROGRESS]:正在优化您的问题...");
            System.out.println("[PROGRESS]:正在优化您的问题...");
        }

        log.info("开始问题改写, 原始问题: {}", query.text());
        List<ChatMessage> chatMemory = query.metadata().chatMemory(); // chatMemory 为会话中的历史上下文

        Stopwatch stopwatch = Stopwatch.createStarted();
        String newQuery = chatModel.chat(createPrompt(query, format(chatMemory)).text());
        log.info("问题改写完成, 改写结果 : {} ,耗时: {}", newQuery, stopwatch.stop().elapsed(TimeUnit.MILLISECONDS));

        Query compressedQuery = query.metadata() == null ? Query.from(newQuery) : Query.from(newQuery, query.metadata());
        log.info("Compressed Success, source query: {}, compressed query: {}", query.text(), compressedQuery.text());

        // 异步回写改写结果到 chat_message
        if (chatMessageId != null) {
            ChatMessageService chatMessageService = getChatMessageService();
            if (chatMessageService != null) {
                Thread.ofVirtual().name("query-transform-" + chatMessageId).start(() -> {
                    try {
                        chatMessageService.updateTransformContent(chatMessageId, newQuery);
                        log.info("改写结果已回写: assistantMsgId={}, transformContent={}", chatMessageId, newQuery);
                    } catch (Exception e) {
                        log.warn("改写结果回写失败: assistantMsgId={}", chatMessageId, e);
                    }
                });
            }
        }
        return singletonList(compressedQuery);
    }

    protected Prompt createPrompt(Query query, String chatMemory) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("query", query.text());
        variables.put("chatMemory", chatMemory);
        return promptTemplate.apply(variables);
    }

    protected String format(List<ChatMessage> chatMemory) {
        return chatMemory.stream()
                .map(this::format)
                .filter(Objects::nonNull)
                .collect(joining("\n"));
    }

    protected String format(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return "User: " + userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            if (aiMessage.hasToolExecutionRequests()) {
                return null;
            }
            return "AI: " + aiMessage.text();
        }
        return null;
    }

}
