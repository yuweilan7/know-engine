package know.engine.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static know.engine.rag.config.ElasticSearchConfiguration.INDEX_NAME;

/** 每轮问答建立两路检索器，两路共用调用方从 MySQL 读出的生效版本列表。 */
@RequiredArgsConstructor
public class KnowEngineElasticsearchContentRetriever implements ContentRetriever {
    private final ElasticsearchClient client;
    private final EmbeddingModel embeddingModel;
    private final List<Long> activeVersionIds;
    private final boolean vectorSearch;

    @Override
    public List<Content> retrieve(dev.langchain4j.rag.query.Query query) {
        if (activeVersionIds.isEmpty()) return List.of();

        List<FieldValue> ids = activeVersionIds.stream().map(id -> FieldValue.of(id.longValue())).toList();
        Query scope = Query.of(q -> q.terms(t -> t.field("metadata.versionId").terms(v -> v.value(ids))));
        SearchRequest.Builder request = new SearchRequest.Builder()
                .index(INDEX_NAME).size(10).allowPartialSearchResults(false)
                .source(s -> s.filter(f -> f.excludes("vector")));
        if (vectorSearch) {
            var vector = embeddingModel.embed(query.text()).content().vectorAsList();
            // 放进 knn.filter，在取 Top K 前过滤，避免历史版占满候选。
            request.query(q -> q.knn(k -> k.field("vector").queryVector(vector).filter(scope)))
                    .minScore(0.5);
        } else {
            request.query(q -> q.bool(b -> b.filter(scope).must(m -> m.match(t -> t.field("text").query(query.text())))));
        }
        try {
            return client.search(request.build(), Document.class).hits().hits().stream()
                    .filter(hit -> hit.source() != null && hit.source().getText() != null)
                    .map(hit -> Content.from(TextSegment.from(hit.source().getText(), new Metadata(hit.source().getMetadata())),
                            Map.of(ContentMetadata.SCORE, hit.score(), ContentMetadata.EMBEDDING_ID, hit.id())))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("知识索引暂不可用", e);
        }
    }
}
