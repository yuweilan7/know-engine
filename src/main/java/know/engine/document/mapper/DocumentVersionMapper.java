package know.engine.document.mapper;

import know.engine.document.entity.DocumentVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * 文档版本表 Mapper 接口
 */
@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {

    /** 按文档ID物理删除该文档的所有版本记录 */
    @Delete("DELETE FROM knowledge_document_version WHERE doc_id = #{docId}")
    int physicalDeleteByDocId(@Param("docId") Long docId);

    /** 按文档ID列表批量物理删除所有版本记录 */
    @Delete("<script>DELETE FROM knowledge_document_version WHERE doc_id IN " +
            "<foreach item='docId' collection='docIds' open='(' separator=',' close=')'>#{docId}</foreach></script>")
    int physicalDeleteByDocIds(@Param("docIds") Collection<Long> docIds);
}
