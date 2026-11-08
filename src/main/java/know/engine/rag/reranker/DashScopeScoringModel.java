package know.engine.rag.reranker;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 通过百炼原生接口对 RRF 召回的候选批量打分。 */
@Component
public class DashScopeScoringModel implements ScoringModel {

    private final RestClient client;
    private final URI endpoint;
    private final String model;

    public DashScopeScoringModel(@Value("${dashscope.api-key}") String apiKey,
                                 @Value("${dashscope.rerank.url}") URI endpoint,
                                 @Value("${dashscope.rerank.model-name}") String model) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("DashScope rerank URL must be an HTTPS endpoint");
        }
        this.endpoint = endpoint;
        this.model = model;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(25));
        this.client = RestClient.builder().requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments.isEmpty()) {
            return Response.from(List.of());
        }

        var payload = Map.of(
                "model", model,
                "input", Map.of("query", query, "documents", segments.stream().map(TextSegment::text).toList()),
                "parameters", Map.of("top_n", segments.size(), "return_documents", false)
        );
        JsonNode response;
        try {
            response = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("DashScope rerank failed with HTTP " + ex.getStatusCode().value());
        }

        // API 按相关性返回结果。ScoringModel 要求分数与原始候选一一对齐。
        var results = response == null ? null : response.path("output").path("results");
        if (results == null || !results.isArray() || results.size() != segments.size()) {
            throw new IllegalStateException("DashScope rerank returned an incomplete result set");
        }
        var scores = new Double[segments.size()];
        for (var result : results) {
            var indexNode = result.path("index");
            var scoreNode = result.path("relevance_score");
            int index = indexNode.intValue();
            double score = scoreNode.doubleValue();
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt() || index < 0 || index >= scores.length
                    || scores[index] != null || !scoreNode.isNumber() || !Double.isFinite(score)) {
                throw new IllegalStateException("DashScope rerank returned an invalid candidate index or score");
            }
            scores[index] = score;
        }
        return Response.from(List.copyOf(Arrays.asList(scores)));
    }
}
