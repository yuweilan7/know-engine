package know.engine.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import know.engine.ai.service.RagChatService;
import know.engine.ai.service.TitleSummaryService;
import know.engine.chat.service.ChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 总结 Model 配置
 * [一]
 * LangChain4j Spring Starter 已根据 {@code application.yml} 中自动注册全局默认模型（{@code openAiChatModel} / {@code openAiStreamingChatModel}）
 *      openAiChatModel 用于意图识别、openAiStreamingChatModel 用于不含 RAG 的 CommonChat

 * [二]
 * 除此之外，本类额外提供与全局配置的模型名、温度等参数不同的专用 Bean，供特定场景使用
 *      titleChatModel 用于会话标题生成、ragChatModel 用于 RAG 检索增强之后的对话生成,即 RagChat
 */
@Configuration
public class AiModelConfig {

    /**
     * 会话标题生成专用非流式模型
     * 供 {@link TitleSummaryService} 通过 {@code @AiService(chatModel = "titleChatModel")} 引用
     * 每次调用独立、无会话记忆，可在虚拟线程中并发复用
     */
    @Bean
    ChatModel titleChatModel(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName) {
        return OpenAiChatModel.builder()
                // 复用 yml 中的 API Key，与全局模型共用同一 DashScope 账号
                .apiKey(apiKey)
                // 复用 yml 中的 OpenAI 兼容接口地址
                .baseUrl(baseUrl)
                .modelName(modelName)
                // 标题允许适当变化，温度高于 RAG 回答模型
                .temperature(0.7)
                // 关闭思考模式，直接返回标题文本
                .customParameters(Map.of("enable_thinking", false))
                // 创建非流式 ChatModel Bean
                .build();
    }

    /**
     * RAG 检索增强之后的对话生成的专用流式模型 （不是指RAG检索时调用模型，RAG检索并不会调用大模型，是指最终回答给用户时的model）
     * 供 {@link ChatService} 在构建
     * {@link RagChatService} 时注入；每轮 RAG 对话仍通过
     * {@code AiServices.builder()} 动态组装，但模型实例从此 Bean 获取
     */
    @Bean
    StreamingChatModel ragChatModel(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.streaming-chat-model.model-name}") String modelName) {
        return OpenAiStreamingChatModel.builder()
                // 复用 yml 中的 API Key
                .apiKey(apiKey)
                // 复用 yml 中的 OpenAI 兼容接口地址
                .baseUrl(baseUrl)
                .modelName(modelName)
                // 使用较低温度，降低知识问答中的随机性
                .temperature(0.2)
                // 限制候选词采样范围，使回答更稳定
                .topP(0.9)
                // 关闭思考模式，直接流式输出最终答案
                .customParameters(Map.of("enable_thinking", false))
                // 创建流式 StreamingChatModel Bean
                .build();
    }
}
