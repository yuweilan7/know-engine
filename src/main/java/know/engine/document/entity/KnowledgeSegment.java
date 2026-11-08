package know.engine.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 某一版的分块。发布后编辑原文须重新上传，分块和向量一起更新。 */
@Getter
@Setter
@TableName("knowledge_segment")
public class KnowledgeSegment extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String text;

    /** 分块的稳定标识，同时作为 ES 文档 _id。索引重试时复用。 */
    private String chunkId;

    /** 标题层级、页码等扩展信息。归属和父子关系以普通列为准。 */
    private String metadata;
    private Long documentId;

    /** 版本表主键，例如 101；界面版本号可能是 2.0.0。 */
    private Long versionId;
    private String parentChunkId;
    private Integer chunkOrder;

    /** 1 表示父块只存 MySQL，召回子块后再取出作为上下文。 */
    private Integer skipEmbedding;
}
