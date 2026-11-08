package know.engine.document.service.impl;

import know.engine.document.entity.DocumentVersion;
import know.engine.document.mapper.DocumentVersionMapper;
import know.engine.document.service.DocumentVersionService;
import know.engine.document.util.VersionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 文档版本表 Service 实现类
 */
@Service
public class DocumentVersionServiceImpl
        extends ServiceImpl<DocumentVersionMapper, DocumentVersion>
        implements DocumentVersionService {

    /**
     * 语义化版本比较器（按 major.minor.patch 数值比较）
     */
    private static final Comparator<DocumentVersion> VERSION_COMPARATOR =
            Comparator.comparing(DocumentVersion::getVersion, VersionUtil::compareVersions);

    @Override
    public List<DocumentVersion> listByDocId(Long docId) {
        List<DocumentVersion> versions = list(new QueryWrapper<DocumentVersion>()
                .eq("doc_id", docId));
        // 在 Java 层按语义版本降序排序
        versions.sort(VERSION_COMPARATOR.reversed());
        return versions;
    }

    @Override
    public String getLatestVersion(Long docId) {
        List<DocumentVersion> versions = listByDocId(docId);
        if (versions.isEmpty()) {
            return null;
        }
        return versions.get(0).getVersion();
    }
}
