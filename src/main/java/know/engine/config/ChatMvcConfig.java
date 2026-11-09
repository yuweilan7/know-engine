package know.engine.config;

import know.engine.chat.service.ChatSettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ChatMvcConfig {
    @Bean
    AsyncTaskExecutor chatSseWriter(ChatSettings settings) {
        var writer = new ThreadPoolTaskExecutor();
        writer.setThreadNamePrefix("mvc-sse-");
        writer.setCorePoolSize(settings.getMaxConcurrent());
        writer.setMaxPoolSize(settings.getMaxConcurrent());
        writer.setQueueCapacity(settings.getMaxConcurrent() * 2);
        return writer;
    }

    @Bean
    WebMvcConfigurer chatAsyncSupport(ChatSettings settings, @Qualifier("chatSseWriter") AsyncTaskExecutor writer) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(writer);
                configurer.setDefaultTimeout(settings.getTotalTimeout().plus(settings.getFinalizeTimeout())
                        .plusSeconds(7).toMillis());
            }
        };
    }
}
