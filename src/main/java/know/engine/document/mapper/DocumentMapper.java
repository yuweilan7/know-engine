package know.engine.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import know.engine.document.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
    /** 发布、提交构建参数、删除都先锁同一条文档记录。外部 I/O 在事务之外。 */
    @Select("SELECT * FROM knowledge_document WHERE doc_id = #{id} AND deleted = 0 FOR UPDATE")
    Document lockLive(@Param("id") long id);

    @Select("""
            SELECT d.active_version_id
            FROM knowledge_document d
            JOIN knowledge_document_version v
              ON v.version_id = d.active_version_id AND v.doc_id = d.doc_id
            WHERE d.deleted = 0 AND v.deleted = 0 AND v.index_status = 'READY'
            """)
    List<Long> activeVersionIds();

    /** 清理需要显式读取已删除文档，绕开 MyBatis-Plus 的逻辑删除过滤。 */
    @Select("""
            SELECT doc_id FROM knowledge_document
            WHERE deleted = 1 AND (cleanup_at IS NULL OR cleanup_at < CURRENT_TIMESTAMP - INTERVAL 1 DAY)
            ORDER BY cleanup_at, doc_id LIMIT 20
            """)
    List<Long> pendingCleanupIds();

    @Update("""
            UPDATE knowledge_document
            SET deleted = 1, active_version_id = NULL, publish_revision = publish_revision + 1
            WHERE doc_id = #{id} AND deleted = 0
            """)
    void retract(@Param("id") long id);

    @Update("""
            UPDATE knowledge_document SET active_version_id = #{versionId},
                publish_revision = publish_revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE doc_id = #{id} AND deleted = 0
            """)
    void publish(@Param("id") long id, @Param("versionId") long versionId);

    @Update("UPDATE knowledge_document SET cleanup_at = CURRENT_TIMESTAMP WHERE doc_id = #{id} AND deleted = 1")
    void markCleaned(@Param("id") long id);
}
