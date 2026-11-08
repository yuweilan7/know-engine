package know.engine.document.service;

import know.engine.document.constant.FileType;
import know.engine.document.entity.Document;

import java.io.InputStream;

/**
 * 文件处理服务 - 负责文档转换处理
 */
public interface FileProcessService {
    /**
     * 处理文档转换
     * 1. 从输入流读取文件内容
     * 2. 调用文档解析接口转换格式
     * 3. 转换后的文档保存在MinIO上
     * 4. 更新文档状态
     *
     * @param document 文档对象
     * @param inputStream 文件输入流
     * @param originalFileName 原始上传文件名（含扩展名），用于解析侧识别类型
     * @return 转换后的文档URL（convertedDocUrl），如果不涉及转换则返回 null
     */
    String processDocument(Document document, InputStream inputStream, String originalFileName);

    /**
     * 判断是否支持该文件
     */
    boolean supports(FileType fileType);
}

