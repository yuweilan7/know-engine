package know.engine.document.controller;

import know.engine.document.entity.KnowledgeSegment;
import know.engine.document.service.SegmentService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 知识片段表 Controller
 */
@RestController
@RequestMapping("/api/segment")
public class SegmentController {

    @Autowired
    private SegmentService segmentService;

    /**
     * 根据ID查询
     */
    @GetMapping("/{id}")
    public KnowledgeSegment getById(@PathVariable Long id) {
        return segmentService.getById(id);
    }

    /**
     * 根据文档ID分页查询片段
     *
     * @param documentId      文档ID
     * @param documentVersion 版本ID（可选，传入时只查该版本分段，不传时查该文档所有版本分段）
     * @param current         当前页
     * @param size            每页大小
     * @return 分页结果
     */
    @GetMapping("/page-by-document")
    public Page<KnowledgeSegment> pageByDocumentId(
            @RequestParam Long documentId,
            @RequestParam(required = false) Long documentVersion,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        Page<KnowledgeSegment> page = new Page<>(current, size);
        QueryWrapper<KnowledgeSegment> wrapper = new QueryWrapper<>();
        wrapper.eq("document_id", documentId);
        if (documentVersion != null) {
            wrapper.eq("document_version", documentVersion);
        }
        wrapper.orderByAsc("chunk_order");
        return segmentService.page(page, wrapper);
    }

    /**
     * 根据文档ID统计片段数量
     *
     * @param documentId      文档ID
     * @param documentVersion 版本ID（可选，传入时只统计该版本分段数量）
     * @return 片段数量
     */
    @GetMapping("/count-by-document")
    public long countByDocumentId(@RequestParam Long documentId, @RequestParam(required = false) Long documentVersion) {
        QueryWrapper<KnowledgeSegment> wrapper = new QueryWrapper<>();
        wrapper.eq("document_id", documentId);
        if (documentVersion != null) {
            wrapper.eq("document_version", documentVersion);
        }
        return segmentService.count(wrapper);
    }

    /**
     * 根据ID更新
     * 禁止直接修改父分段（skipEmbedding=1），父分段内容由子分段修改时自动同步。
     * 如需修改父分段内容，请修改对应的子分段。
     */
    @PutMapping
    public boolean updateById(@RequestBody KnowledgeSegment segment) {
        KnowledgeSegment existing = segmentService.getById(segment.getId());
        if (existing != null && existing.getSkipEmbedding() != null && existing.getSkipEmbedding() == 1) {
            throw new IllegalArgumentException("父分段不支持直接修改，请修改对应的子分段");
        }
        return segmentService.updateById(segment, true);
    }

    /**
     * 根据ID删除
     */
    @DeleteMapping("/{id}")
    public boolean removeById(@PathVariable Long id) {
        return segmentService.removeById(id);
    }
}
