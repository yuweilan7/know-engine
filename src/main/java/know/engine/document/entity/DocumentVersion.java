package know.engine.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import know.engine.document.constant.DocumentStatus;
import know.engine.document.constant.IndexStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 一次文档修订。原文件、解析结果和切片参数都归属于这一版。 */
@Getter
@Setter
@TableName("knowledge_document_version")
public class DocumentVersion extends BaseEntity {
    /** 数据库主键。界面显示的版本号由 version 单独保存。 */
    @TableId(type = IdType.AUTO)
    private Long versionId;
    private Long docId;
    private String version;

    /** 对象存储中的相对路径。下载地址由当前存储配置生成。 */
    private String sourceObjectKey;
    private String parsedObjectKey;
    private String contentHash;
    private String uploadUser;
    private String changelog;

    /** 原文件、解析、切片已经完成到哪一步。失败时保留已完成的成果。 */
    private DocumentStatus buildStatus;

    /** 单独记录 ES 索引的进度。READY 之后仍需发布，才能进入问答范围。 */
    private IndexStatus indexStatus;

    /** 用户为这一版提交的切片参数 JSON。切片成功后随分块一起固定。 */
    private String splitParam;

    /** 构建请求看到的发布修订号。为空时只建索引，等待用户发布。 */
    private Long basePublishRevision;

    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
}
