package know.engine.document.service;

import know.engine.document.entity.DocumentSplitParam;
import know.engine.document.entity.DocumentUploadParam;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentVersion;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文档处理服务接口
 * 负责文档的业务流程处理：上传、转换、分段、向量化
 */
public interface DocumentProcessService {

    /**
     * 上传文件
     * @param documentUploadParam 上传参数
     * @param uploadUser 上传用户
     * @return 保存后的文档记录
     * @throws IOException IO异常
     */
    Document upload(DocumentUploadParam documentUploadParam, String uploadUser) throws IOException;

    /**
     * 上传文档新版本
     * @param docId     文档ID（knowledge_document.doc_id）
     * @param version   新版本号（语义化版本，如 "2.0.0"，必须大于现有最大版本号）
     * @param file      新版本文件
     * @param uploadUser 上传用户
     * @param changelog 版本变更说明（可选）
     * @return 更新后的文档记录
     * @throws IOException IO异常
     */
    Document uploadNewVersion(Long docId, String version, MultipartFile file, String uploadUser, String changelog) throws IOException;

    /**
     * 对文档进行切分
     * 使用 MarkdownHeaderParentTextSplitter 进行切分
     *
     * @param document 文档ID
     * @return 切分后的片段数量
     */
    int split(Document document, DocumentSplitParam documentSplitParam);

    /**
     * 向量化并存储
     *
     * @param documentVersion
     * @return 是否成功
     */
    boolean embedAndStore(DocumentVersion documentVersion);

    /**
     * 切换文档到指定版本
     * 将文档的当前激活版本切换为目标版本，清理旧版本分段和向量，恢复目标版本的文件URL和状态
     *
     * @param docId     文档ID
     * @param versionId 目标版本ID
     * @return 更新后的文档记录
     */
    Document switchVersion(Long docId, Long versionId);

}
