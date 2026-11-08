package know.engine.ai.prompt;

import dev.langchain4j.model.input.PromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词加载服务，从 classpath 读取 {@code prompts/} 下的文本文件并缓存
 * 路径常量见 {@link know.engine.ai.constant.PromptResources}
 */
@Service
public class PromptService {
    /** 提示词文本缓存，key 为 classpath 相对路径 */
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /**
     * 根据 classpath 资源路径获取提示词原文（首次加载后缓存）
     *
     * @param resourcePath 如 {@code prompts/generic-qa-prompt.txt}
     */
    public String getPrompt(String resourcePath) {
        return promptCache.computeIfAbsent(resourcePath, this::loadPromptFromFile);
    }

    /**
     * 根据 classpath 资源路径创建 LangChain4j 提示词模板，支持 {@code {{variable}}} 占位符
     */
    public PromptTemplate getPromptTemplate(String resourcePath) {
        return PromptTemplate.from(getPrompt(resourcePath));
    }

    /** 从 classpath 文件加载提示词，文件缺失时抛出 {@link IllegalStateException} */
    private String loadPromptFromFile(String resourcePath) {
        try {
            Resource resource = resolver.getResource("classpath:" + resourcePath);
            return FileCopyUtils.copyToString(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("提示词文件缺失: " + resourcePath, e);
        }
    }
}
