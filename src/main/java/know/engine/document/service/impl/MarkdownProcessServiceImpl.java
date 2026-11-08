package know.engine.document.service.impl;

import know.engine.document.constant.FileType;
import know.engine.document.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown 沿用原有的图片描述增强，输出到当前版本的解析目录。 */
@Slf4j
@Service
public class MarkdownProcessServiceImpl extends MinerUProcessBaseServiceImpl {

    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[(.*?)\\]\\(([^)]+)\\)");

    @Autowired
    private FileStorageService fileStorageService;

    @Override
    public String processDocument(InputStream inputStream, String originalFileName, String outputPrefix) {
        try {
            String text = readInputStreamAsString(inputStream);
            String key = outputPrefix + "document.md";
            fileStorageService.uploadFile(key, enrichImageDescriptions(text).getBytes(StandardCharsets.UTF_8), "text/markdown");
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Markdown 解析失败", e);
        }
    }

    /**
     * 遍历 Markdown 中的图片标签，为每张图片调用视觉模型生成描述并替换 alt 文本
     * 例如：![image](https://xxx/foo.jpg) -> ![这是一张xxx图片，xxx描述](https://xxx/foo.jpg)
     */
    private String enrichImageDescriptions(String mdContent) {
        Matcher matcher = IMAGE_PATTERN.matcher(mdContent);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String originAlt = matcher.group(1);
            String imageUrl = matcher.group(2);

            String description;
            try {
                // 调用基类提供的视觉模型生成描述
                description = generateImageDescription(imageUrl);
                if (description == null || description.isBlank()) {
                    log.warn("图片描述为空，保留原 alt 文本，url: {}", imageUrl);
                    description = originAlt;
                } else {
                    // 去除可能存在的换行，保证 Markdown 标签单行
                    description = description.replaceAll("[\\r\\n]+", " ").trim();
                }
                log.info("图片描述已生成: {} -> {}", imageUrl, description);
            } catch (Exception e) {
                log.warn("生成图片描述失败，保留原 alt 文本，url: {}", imageUrl, e);
                description = originAlt;
            }

            String newImageTag = "![" + description + "](" + imageUrl + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(newImageTag));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 将 InputStream 全量读取为字符串
     */
    private String readInputStreamAsString(InputStream inputStream) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.MARKDOWN;
    }
}
