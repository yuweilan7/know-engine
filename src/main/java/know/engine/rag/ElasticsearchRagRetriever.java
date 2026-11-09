package know.engine.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import know.engine.chat.service.OnlineChatWork;
import know.engine.rag.RagContext.ChunkKey;
import know.engine.rag.RagContext.Hit;
import lombok.RequiredArgsConstructor;
import org.apache.http.client.config.RequestConfig;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static know.engine.rag.config.ElasticSearchConfiguration.INDEX_NAME;
import static know.engine.rag.constant.MetadataKeyConstant.*;

/** 一路检索调用只读调用方提供的版本快照。原始名次和分数随命中返回，后续由 RRF 负责融合。 */
@Component
@RequiredArgsConstructor
public class ElasticsearchRagRetriever {
    private static final RequestOptions OPTIONS = RequestOptions.DEFAULT.toBuilder()
            .setRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(250)
                    .setConnectTimeout(700).setSocketTimeout(1500).build()).build();

    private final RestClient client;
    private final ObjectMapper json;

    public List<Hit> retrieve(String query, List<Long> scope, List<Float> vector) throws Exception {
        if (scope.isEmpty()) return List.of();
        var filter = Map.of("terms", Map.of("metadata.versionId", scope));
        var body = json.createObjectNode().put("size", 10).put("timeout", "750ms");
        body.set("_source", json.valueToTree(Map.of("excludes", List.of("vector"))));
        if (vector == null) {
            body.set("query", json.valueToTree(Map.of("bool",
                    Map.of("filter", filter, "must", Map.of("match", Map.of("text", query))))));
        } else {
            // 在 KNN 选取 Top K 前限制生效版本，避免历史版本挤占候选。
            body.put("min_score", 0.5);
            body.set("query", json.valueToTree(Map.of("knn",
                    Map.of("field", "vector", "query_vector", vector, "filter", filter))));
        }
        var request = new Request("POST", "/" + INDEX_NAME + "/_search");
        request.addParameter("allow_partial_search_results", "false");
        request.setJsonEntity(json.writeValueAsString(body));
        request.setOptions(OPTIONS);

        // 先登记取消动作。注册与网络请求返回 Cancellable 之间发生取消时，拿到句柄后立即关闭请求。
        var cancelled = new AtomicBoolean();
        var pending = new CompletableFuture<Response>();
        var transport = new CompletableFuture<org.elasticsearch.client.Cancellable>();
        try (var registration = OnlineChatWork.onTransportCancel(() -> {
            cancelled.set(true);
            transport.thenAccept(org.elasticsearch.client.Cancellable::cancel);
            pending.cancel(false);
        })) {
            if (cancelled.get()) throw new InterruptedException("Search cancelled");
            transport.complete(client.performRequestAsync(request, new ResponseListener() {
                @Override public void onSuccess(Response response) { pending.complete(response); }
                @Override public void onFailure(Exception error) { pending.completeExceptionally(error); }
            }));
            try (var content = pending.get().getEntity().getContent()) {
                return hits(json.readTree(content), scope);
            }
        } catch (InterruptedException e) {
            transport.thenAccept(org.elasticsearch.client.Cancellable::cancel);
            throw e;
        }
    }

    private List<Hit> hits(JsonNode result, List<Long> scope) {
        var hits = result.path("hits").path("hits");
        if (result.path("timed_out").asBoolean() || result.path("_shards").path("failed").asInt() != 0
                || !hits.isArray()) throw new IllegalStateException("Incomplete Elasticsearch response");
        var output = new ArrayList<Hit>();
        for (JsonNode hit : hits) {
            var source = hit.path("_source");
            var metadata = source.path("metadata");
            var doc = metadata.path(DOC_ID);
            var version = metadata.path(VERSION_ID);
            String chunk = metadata.path(CHUNK_ID).asText("");
            String id = hit.path("_id").asText("");
            String content = source.path("text").asText("");
            if (!doc.isIntegralNumber() || !doc.canConvertToLong() || !version.isIntegralNumber()
                    || !version.canConvertToLong() || !scope.contains(version.longValue()) || chunk.isBlank()
                    || !chunk.equals(id) || content.isBlank()) {
                throw new IllegalStateException("Elasticsearch hit does not match the document/version/chunk mapping");
            }
            JsonNode score = hit.path("_score");
            output.add(new Hit(new ChunkKey(doc.longValue(), version.longValue(), chunk), id, content,
                    metadata.path(PARENT_CHUNK_ID).asText(null), metadata.path(FILE_NAME).asText(null),
                    metadata.path(URL).asText(null), output.size() + 1, score.isNumber() ? score.doubleValue() : null));
        }
        return List.copyOf(output);
    }
}
