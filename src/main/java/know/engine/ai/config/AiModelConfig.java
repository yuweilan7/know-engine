package know.engine.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * 显式配置聊天主链使用的 LangChain4j 低层模型。Bean 名和调用方的 Qualifier 对应。
 * 意图识别、改写和标题使用同步模型，生成使用流式模型。百炼地址、模型名和密钥由外部配置提供。
 * 这些 Bean 共用可取消的 HTTP 传输，调用方负责路由、上下文、超时和结果持久化。
 */
@Configuration
public class AiModelConfig {
    @Bean
    ChatModel onlineChatModel(OnlineModelHttpClient transport,
                              @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
                              @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
                              @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName) {
        return chatBuilder(transport, apiKey, baseUrl, modelName)
                .maxCompletionTokens(512)
                .build();
    }

    @Bean
    ChatModel titleChatModel(OnlineModelHttpClient transport,
                             @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
                             @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
                             @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName) {
        return chatBuilder(transport, apiKey, baseUrl, modelName)
                .temperature(0.7)
                .maxCompletionTokens(128)
                .build();
    }

    private OpenAiChatModel.OpenAiChatModelBuilder chatBuilder(
            OnlineModelHttpClient transport, String apiKey, String baseUrl, String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey).baseUrl(baseUrl).modelName(modelName)
                .httpClientBuilder(transport.builder())
                .timeout(Duration.ofMillis(2500))
                .maxRetries(0)
                .customParameters(Map.of("enable_thinking", false));
    }

    @Bean
    StreamingChatModel ragChatModel(OnlineModelHttpClient transport,
                                    @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
                                    @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
                                    @Value("${langchain4j.open-ai.streaming-chat-model.model-name}") String modelName) {
        // 知识问答使用低温度，并通过 ChatService 注入检索上下文。
        return streamBuilder(transport, apiKey, baseUrl, modelName)
                .temperature(0.2).topP(0.9)
                .build();
    }

    @Bean
    StreamingChatModel onlineCommonChatModel(OnlineModelHttpClient transport,
                                            @Value("${langchain4j.open-ai.streaming-chat-model.api-key}") String apiKey,
                                            @Value("${langchain4j.open-ai.streaming-chat-model.base-url}") String baseUrl,
                                            @Value("${langchain4j.open-ai.streaming-chat-model.model-name}") String modelName) {
        return streamBuilder(transport, apiKey, baseUrl, modelName).build();
    }

    private OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamBuilder(
            OnlineModelHttpClient transport, String apiKey, String baseUrl, String modelName) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey).baseUrl(baseUrl).modelName(modelName)
                .httpClientBuilder(transport.builder())
                .timeout(Duration.ofSeconds(10))
                .maxCompletionTokens(2048)
                .customParameters(Map.of("enable_thinking", false));
    }
}
