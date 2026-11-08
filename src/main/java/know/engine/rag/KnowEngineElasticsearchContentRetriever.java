package know.engine.rag;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.transport.rest_client.RestClientOptions;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import lombok.RequiredArgsConstructor;
import know.engine.chat.service.OnlineChatWork;
import org.apache.http.client.config.RequestConfig;
import org.elasticsearch.client.RequestOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static know.engine.rag.config.ElasticSearchConfiguration.INDEX_NAME;

/** 每轮问答建立两路检索器，两路共用调用方从 MySQL 读出的生效版本列表。 */
@RequiredArgsConstructor
public class KnowEngineElasticsearchContentRetriever {
    private static final RestClientOptions SEARCH_OPTIONS = new RestClientOptions(
            RequestOptions.DEFAULT.toBuilder().setRequestConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(250).setConnectTimeout(700).setSocketTimeout(1500).build()).build(),
            false);

    private final ElasticsearchAsyncClient client;
    private final List<Long> activeVersionIds;

    public List<Content> retrieve(String query, List<Float> vector) throws InterruptedException {
        if (activeVersionIds.isEmpty()) return List.of();

        List<FieldValue> ids = activeVersionIds.stream().map(id -> FieldValue.of(id.longValue())).toList();
        Query scope = Query.of(q -> q.terms(t -> t.field("metadata.versionId").terms(v -> v.value(ids))));
        SearchRequest.Builder request = new SearchRequest.Builder()
                .index(INDEX_NAME).size(10).allowPartialSearchResults(false).timeout("750ms")
                .source(s -> s.filter(f -> f.excludes("vector")));
        if (vector != null) {
            // 放进 knn.filter，在取 Top K 前过滤，避免历史版占满候选。
            request.query(q -> q.knn(k -> k.field("vector").queryVector(vector).filter(scope)))
                    .minScore(0.5);
        } else {
            request.query(q -> q.bool(b -> b.filter(scope).must(m -> m.match(t -> t.field("text").query(query)))));
        }
        var pending = client.withTransportOptions(SEARCH_OPTIONS).search(request.build(), Document.class);
        OnlineChatWork.onTransportCancel(() -> pending.cancel(true));
        try {
            var response = pending.get();
            if (response.timedOut()) throw new IllegalStateException("知识索引查询超时");
            return response.hits().hits().stream()
                    .filter(hit -> hit.source() != null && hit.source().getText() != null)
                    .map(hit -> Content.from(TextSegment.from(hit.source().getText(), new Metadata(hit.source().getMetadata())),
                            Map.of(ContentMetadata.SCORE, hit.score(), ContentMetadata.EMBEDDING_ID, hit.id())))
                    .toList();
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IllegalStateException("知识索引暂不可用", e.getCause());
        }
    }
}
