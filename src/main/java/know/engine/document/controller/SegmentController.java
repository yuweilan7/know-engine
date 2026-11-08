package know.engine.document.controller;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import know.engine.document.entity.KnowledgeSegment;
import know.engine.document.service.DocumentService;
import know.engine.document.service.SegmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/segment")
@RequiredArgsConstructor
public class SegmentController {
    private final SegmentService segments;
    private final DocumentService documents;

    @GetMapping("/{id}")
    public KnowledgeSegment get(@PathVariable Long id) {
        KnowledgeSegment segment = segments.getById(id);
        if (segment == null || documents.getById(segment.getDocumentId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return segment;
    }

    @GetMapping("/page-by-document")
    public Page<KnowledgeSegment> page(@RequestParam Long documentId, @RequestParam(required = false) Long versionId,
                                       @RequestParam(defaultValue = "1") Integer current,
                                       @RequestParam(defaultValue = "10") Integer size) {
        return forVersion(documentId, versionId).orderByAsc(KnowledgeSegment::getChunkOrder)
                .page(new Page<>(Math.max(1, current), Math.max(1, Math.min(200, size))));
    }

    @GetMapping("/count-by-document")
    public long count(@RequestParam Long documentId, @RequestParam(required = false) Long versionId) {
        return forVersion(documentId, versionId).count();
    }

    /** 未指定版本时只展示当前生效版。分块只读，编辑内容从上传新版本开始。 */
    private LambdaQueryChainWrapper<KnowledgeSegment> forVersion(long docId, Long versionId) {
        var doc = documents.getById(docId);
        if (doc == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Long selected = versionId == null ? doc.getActiveVersionId() : versionId;
        return segments.lambdaQuery().eq(KnowledgeSegment::getDocumentId, docId)
                .eq(KnowledgeSegment::getVersionId, selected == null ? -1L : selected);
    }
}
