package know.engine.document.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import know.engine.document.entity.KnowledgeSegment;
import know.engine.document.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static know.engine.rag.config.ElasticSearchConfiguration.INDEX_NAME;
import static know.engine.rag.constant.MetadataKeyConstant.*;

@Service
@RequiredArgsConstructor
public class VectorStoreServiceImpl implements VectorStoreService {
    private final ElasticsearchClient client;
    private final OpenAiEmbeddingModel embeddingModel;

    @Override
    public void embedAndStore(List<KnowledgeSegment> segments, String title, String sourceUrl) {
        List<TextSegment> texts = segments.stream().map(segment -> {
            Map<String, Object> metadata = new HashMap<>();
            if (segment.getMetadata() != null) metadata.putAll(JSON.parseObject(segment.getMetadata()));
            // ID 取自普通列，避免旧 JSON 中的类型和归属字段覆盖数据库记录。
            metadata.remove(VERSION);
            metadata.remove(PARENT_CHUNK_ID);
            metadata.put(DOC_ID, segment.getDocumentId());
            metadata.put(VERSION_ID, segment.getVersionId());
            metadata.put(CHUNK_ID, segment.getChunkId());
            metadata.put(FILE_NAME, title);
            if (sourceUrl != null) metadata.put(URL, sourceUrl);
            else metadata.remove(URL);
            if (segment.getParentChunkId() != null) metadata.put(PARENT_CHUNK_ID, segment.getParentChunkId());
            return TextSegment.from(segment.getText(), new Metadata(metadata));
        }).toList();
        var embeddings = embeddingModel.embedAll(texts).content();
        if (embeddings.size() != texts.size()) throw new IllegalStateException("向量数量与分块数量不一致");

        BulkRequest.Builder bulk = new BulkRequest.Builder().requireAlias(true);
        for (int i = 0; i < texts.size(); i++) {
            String id = segments.get(i).getChunkId();
            Map<String, Object> document = Map.of("text", texts.get(i).text(),
                    "metadata", texts.get(i).metadata().toMap(), "vector", embeddings.get(i).vector());
            // 重试仍写同一个 _id，覆盖同一分块，不增加记录。
            bulk.operations(op -> op.index(index -> index.index(INDEX_NAME).id(id).document(document)));
        }
        try {
            var result = client.bulk(bulk.build());
            if (result.errors()) {
                var failed = result.items().stream().filter(item -> item.error() != null).findFirst().orElseThrow();
                throw new IllegalStateException("ES 批量写入失败: " + failed.error().reason());
            }
        } catch (IOException e) {
            throw new IllegalStateException("ES 批量写入失败", e);
        }
    }

    @Override
    public void verifySearchable(long docId, long versionId, int expected) {
        try {
            // 写入成功后还要刷新，确认整版能被 search 看见，再允许发布。
            var refresh = client.indices().refresh(r -> r.index(INDEX_NAME));
            if (refresh.shards().failed().longValue() != 0) throw new IllegalStateException("ES 刷新失败");
            var count = client.count(r -> r.index(INDEX_NAME).query(q -> q.bool(b -> b
                    .filter(idQuery(DOC_ID, docId)).filter(idQuery(VERSION_ID, versionId)))));
            if (count.shards().failed().longValue() != 0 || count.count() != expected) {
                throw new IllegalStateException("ES 可检索分块数为 " + count.count() + "，预期 " + expected);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法核验 ES 索引", e);
        }
    }

    @Override
    public void removeByDocId(long docId) {
        try {
            var result = client.deleteByQuery(r -> r.index(INDEX_NAME).query(idQuery(DOC_ID, docId)).refresh(true));
            if (result.timedOut() || !result.failures().isEmpty()
                    || (result.versionConflicts() != null && result.versionConflicts() > 0)) {
                throw new IllegalStateException("ES 删除尚未完成，等待补偿");
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法删除 ES 文档索引", e);
        }
    }

    private static Query idQuery(String field, long value) {
        return Query.of(q -> q.term(t -> t.field("metadata." + field).value(value)));
    }
}
