package know.engine.document.service.impl;

import know.engine.ai.constant.PromptResources;
import know.engine.ai.prompt.PromptService;
import know.engine.document.constant.DocumentStatus;
import know.engine.document.entity.Document;
import know.engine.document.service.FileProcessService;
import know.engine.document.service.FileStorageService;
import know.engine.document.service.DocumentService;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文件处理服务 - 负责文档转换处理
 */
@Slf4j
public abstract class MinerUProcessBaseServiceImpl implements FileProcessService {

    private static final String CONVERTED_FILE_DIR = "converted/";

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private PromptService promptService;

    @Value("${file.parse.api.url:http://localhost:8000}")
    private String fileParseApiUrl;

    @Value("${file.parse.api.connectTimeout:30000}")
    private int connectTimeout;

    @Value("${file.parse.api.responseTimeout:300000}")
    private int responseTimeout;

    /**
     * 处理文档转换
     */
    public String processDocument(Document document, InputStream inputStream, String originalFileName) {
        return processDocumentToMarkdownFromZip(document, inputStream, originalFileName);
    }

    /**
     * 处理文档转换为 Markdown（经 ZIP）：
     * 1. 调用文档解析接口获取 ZIP（包含 Markdown 和图片）
     * 2. 保存 ZIP 到本地磁盘
     * 3. 解压 ZIP 文件
     * 4. 上传解压后的 md 和图片到 MinIO
     * 5. 替换 md 中的图片地址为 MinIO 地址
     * 6. 调用 LLM 生成图片描述并更新 md
     * 7. 保存 md 的 MinIO 地址到 convertedUrl
     * 8. 异步清理本地临时文件
     *
     * @param document 文档对象
     * @param originalFileName 原始上传文件名（含扩展名）
     */
    public String processDocumentToMarkdownFromZip(Document document, InputStream inputStream, String originalFileName) {
        log.info("开始处理文档转换为 ZIP，documentId: {}", document.getDocTitle());

        // 更新状态为转换中
        documentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTING);

        String zipFilePath = null;
        String extractDir = null;

        try {
            // MinerU 在 Windows 上无法用含中文/非法字符的 multipart 文件名落盘，改用 ASCII 安全名并保留真实扩展名
            String safeUploadFileName = buildSafeUploadFileName(originalFileName, document.getDocTitle());

            // 1. 调用文档解析获取 ZIP 格式响应
            byte[] zipBytes = parseDocumentToZip(safeUploadFileName, inputStream);

            // 2. 保存 ZIP 到本地临时目录
            String tempDir = System.getProperty("java.io.tmpdir");
            String uniqueId = UUID.randomUUID().toString();
            zipFilePath = tempDir + File.separator + uniqueId + ".zip";
            extractDir = tempDir + File.separator + uniqueId + "_extracted";

            Files.write(Paths.get(zipFilePath), zipBytes);
            log.info("ZIP 文件已保存到本地: {}", zipFilePath);

            // 3. 解压 ZIP 文件
            extractZip(zipFilePath, extractDir);
            log.info("ZIP 文件已解压到: {}", extractDir);

            // 4. 上传解压后的 md 和图片到 MinIO，并处理 md 内容
            String mdMinioUrl = processExtractedFiles(document, extractDir);

            // 5. 更新文档状态为已转换
            documentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);

            log.info("文档 ZIP 转换完成，documentId: {}, mdUrl: {}", document.getDocTitle(), mdMinioUrl);
            return mdMinioUrl;
        } catch (Exception e) {
            log.error("文档 ZIP 转换失败，documentId: {}", document.getDocTitle(), e);
            // 转换失败，状态回滚为 UPLOADED
            document.setStatus(DocumentStatus.UPLOADED);
            boolean result = documentService.updateById(document);
            Assert.isTrue(result, "文件UPLOADED状态更新失败");
            throw new RuntimeException("文档 ZIP 转换失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
            // 异步清理临时文件
            cleanupTempFilesAsync(zipFilePath, extractDir);
        }
    }

    /**
     * 解压 ZIP 文件到指定目录
     */
    private void extractZip(String zipFilePath, String extractDir) throws IOException {
        Path extractPath = Paths.get(extractDir);
        Files.createDirectories(extractPath);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = extractPath.resolve(entry.getName());

                // 安全检查：防止 ZIP 路径遍历攻击
                if (!entryPath.normalize().startsWith(extractPath.normalize())) {
                    log.warn("跳过不安全的 ZIP 条目: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 处理解压后的文件：上传 md 和图片到 MinIO，替换图片地址，生成图片描述
     */
    private String processExtractedFiles(Document document, String extractDir) throws Exception {
        Path extractPath = Paths.get(extractDir);

        // 查找所有的 md 文件和图片文件
        Path mdFile = null;
        java.util.List<Path> imageFiles = new java.util.ArrayList<>();

        try (Stream<Path> paths = Files.walk(extractPath)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) {
                    String fileName = path.getFileName().toString().toLowerCase();
                    if (fileName.endsWith(".md")) {
                        mdFile = path;
                    } else if (fileName.endsWith(".png") || fileName.endsWith(".jpg") ||
                            fileName.endsWith(".jpeg") || fileName.endsWith(".gif") ||
                            fileName.endsWith(".webp") || fileName.endsWith(".bmp")) {
                        imageFiles.add(path);
                    }
                }
            }
        }

        if (mdFile == null) {
            throw new RuntimeException("解压后的文件夹中未找到 Markdown 文件");
        }

        log.info("找到 Markdown 文件: {}, 图片文件数量: {}", mdFile, imageFiles.size());

        // 上传图片到 MinIO，并建立本地文件名到 MinIO URL 的映射
        java.util.Map<String, String> imageUrlMap = new java.util.HashMap<>();
        String baseObjectName = CONVERTED_FILE_DIR + document.getDocTitle() + "/";

        for (Path imagePath : imageFiles) {
            String imageName = imagePath.getFileName().toString();
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String contentType = getImageContentType(imageName);
            String objectName = baseObjectName + "images/" + imageName;
            String imageUrl = fileStorageService.uploadFile(objectName, imageBytes, contentType);
            imageUrlMap.put(imageName, imageUrl);
            log.info("图片已上传到 MinIO: {} -> {}", imageName, imageUrl);
        }

        // 读取 md 文件内容
        String mdContent = Files.readString(mdFile, StandardCharsets.UTF_8);

        // 替换 md 中的图片地址为 MinIO 地址，并生成图片描述
        String processedMdContent = processMarkdownImages(mdContent, imageUrlMap);

        // 上传处理后的 md 文件到 MinIO
        String mdObjectName = baseObjectName + mdFile.getFileName().toString();
        String mdUrl = fileStorageService.uploadFile(mdObjectName, processedMdContent.getBytes(StandardCharsets.UTF_8), "text/markdown");
        log.info("Markdown 文件已上传到 MinIO: {}", mdUrl);

        return mdUrl;
    }

    /**
     * 获取图片的 Content-Type
     */
    private String getImageContentType(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".png")) return "image/png";
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return "image/jpeg";
        if (lowerName.endsWith(".gif")) return "image/gif";
        if (lowerName.endsWith(".webp")) return "image/webp";
        if (lowerName.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    /**
     * 处理 Markdown 中的图片标签：替换地址并生成图片描述
     * 匹配格式: ![](xxx.png) 或 ![alt](xxx.png)
     */
    private String processMarkdownImages(String mdContent, java.util.Map<String, String> imageUrlMap) {
        // 匹配图片标签的正则表达式: ![alt](path)
        Pattern pattern = Pattern.compile("!\\[(.*?)\\]\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(mdContent);

        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String altText = matcher.group(1);
            String imagePath = matcher.group(2);

            // 提取图片文件名
            String imageName = Paths.get(imagePath).getFileName().toString();

            // 获取 MinIO 上的图片 URL
            String minioUrl = imageUrlMap.get(imageName);
            if (minioUrl == null) {
                // 如果找不到对应的 MinIO URL，保持原样
                log.warn("未找到图片 {} 对应的 MinIO URL", imageName);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            // 生成图片描述（mock 实现）
            String imageDescription = generateImageDescription(minioUrl);

            // 构建新的图片标签: ![描述](minio_url)
            String newImageTag = "![" + imageDescription + "](" + minioUrl + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(newImageTag));

            log.info("图片标签已处理: {} -> {}", imagePath, minioUrl);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String chatModelApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String chatModelBaseUrl;

    /**
     * 生成图片描述
     * 需要注意的是，如果你用的是外部的模型，这个url需要是公网可以访问的url。否则模型需要能和MinIO进行内网通信。
     */
    public String generateImageDescription(String imageUrl) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(chatModelApiKey)
                .baseUrl(chatModelBaseUrl)
                .modelName("qwen3-vl-plus")
                .temperature(0.7)
                .logResponses(true)
                .logRequests(true)
                .build();

        UserMessage userMessage = UserMessage.from(
                new TextContent(promptService.getPrompt(PromptResources.IMAGE_DESCRIPTION_USER)),
                new ImageContent(imageUrl)
        );
        return chatModel.chat(userMessage).aiMessage().text();
    }

    /**
     * 异步清理临时文件
     */
    private void cleanupTempFilesAsync(String zipFilePath, String extractDir) {
        if (zipFilePath == null && extractDir == null) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                // 删除 ZIP 文件
                if (zipFilePath != null) {
                    Files.deleteIfExists(Paths.get(zipFilePath));
                    log.info("临时 ZIP 文件已删除: {}", zipFilePath);
                }

                // 删除解压目录
                if (extractDir != null) {
                    deleteDirectory(Paths.get(extractDir));
                    log.info("临时解压目录已删除: {}", extractDir);
                }
            } catch (Exception e) {
                log.warn("清理临时文件失败", e);
            }
        });
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((a, b) -> -a.compareTo(b)) // 反向排序，先删除子文件/目录
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除文件失败: {}", path, e);
                        }
                    });
        }
    }

    /**
     * 生成发给 MinerU 的 ASCII 安全文件名，保留真实扩展名以便类型识别。
     * Windows 下含中文/乱码成 "?" 的文件名会导致 Errno 22。
     */
    private String buildSafeUploadFileName(String originalFileName, String docTitle) {
        String source = (originalFileName != null && !originalFileName.isBlank())
                ? originalFileName
                : docTitle;
        if (source == null || source.isBlank()) {
            source = "upload.bin";
        }

        String extension = "";
        int lastDot = source.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < source.length() - 1) {
            String ext = source.substring(lastDot + 1);
            if (ext.matches("[A-Za-z0-9]+")) {
                extension = "." + ext.toLowerCase();
            }
        }

        String safeName = "upload-" + UUID.randomUUID() + extension;
        log.info("MinerU 上传文件名已规范化: original={}, safe={}", source, safeName);
        return safeName;
    }

    /**
     * 调用文件解析接口，获取 ZIP 格式响应
     * 使用 Apache HttpClient 5，支持流式下载大文件
     *
     * @param fileName   文件名
     * @param fileStream 文件输入流
     * @return ZIP 文件字节数组
     */
    private byte[] parseDocumentToZip(String fileName, InputStream fileStream) {
        String url = fileParseApiUrl + "/file_parse";

        // 配置请求超时
        RequestConfig requestConfig = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout)).setResponseTimeout(Timeout.ofMilliseconds(responseTimeout)).build();

        try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build()) {

            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Accept", "application/json");

            // 构建 multipart 请求体，启用 ZIP 格式和返回图片
            HttpEntity multipartEntity = MultipartEntityBuilder.create()
                    .setCharset(StandardCharsets.UTF_8)
                    .addBinaryBody("files", fileStream, org.apache.hc.core5.http.ContentType.APPLICATION_OCTET_STREAM, fileName)
                    .addTextBody("backend", "pipeline").addTextBody("response_format_zip", "true")
                    .addTextBody("return_images", "true").addTextBody("return_model_output", "false")
                    .addTextBody("return_middle_json", "false").build();

            httpPost.setEntity(multipartEntity);

            log.info("开始调用文件解析接口（ZIP 模式）: {}", url);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getCode();
                log.info("文件解析接口响应状态码: {}", statusCode);

                HttpEntity responseEntity = response.getEntity();
                if (statusCode == 200 && responseEntity != null) {
                    // 读取响应体为字节数组（ZIP 文件）
                    byte[] zipBytes = EntityUtils.toByteArray(responseEntity);
                    log.info("文件解析接口调用成功，ZIP 文件大小: {} bytes", zipBytes.length);
                    return zipBytes;
                } else {
                    String responseBody = responseEntity != null ? EntityUtils.toString(responseEntity, "UTF-8") : "";
                    log.error("文件解析接口调用失败，状态码: {}, 响应: {}", statusCode, responseBody);
                    throw new RuntimeException("文件解析接口调用失败: HTTP " + statusCode + ", " + responseBody);
                }
            }

        } catch (Exception e) {
            log.error("调用文件解析接口异常", e);
            throw new RuntimeException("调用文件解析接口失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(fileStream);
        }
    }

    /**
     * 安静关闭输入流，忽略异常
     *
     * @param inputStream 输入流
     */
    private void closeQuietly(InputStream inputStream) {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}

