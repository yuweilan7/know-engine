package know.engine.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 资料本身。构建进度在版本表中，这里只记录哪一版已经对问答生效。 */
@Getter
@Setter
@TableName("knowledge_document")
public class Document extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long docId;
    private String docTitle;
    private String description;
    private String extension;

    /** 指向版本表的主键。第一版构建完成前为空。 */
    private Long activeVersionId;

    /** 只随发布和删除递增。编辑标题、描述使用 BaseEntity 的 lockVersion。 */
    private Long publishRevision;
}
