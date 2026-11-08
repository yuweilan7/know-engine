package know.engine.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@EnableConfigurationProperties(ElasticSearchProperties.class)
@RequiredArgsConstructor
public class ElasticSearchConfiguration {
    /** 读写同一个别名。变更模型或 mapping 时在维护窗口切换到新的物理索引。 */
    public static final String INDEX_NAME = "know-engine-chunks";
    private static final String FIRST_INDEX = "know-engine-chunks-001";

    private final ElasticSearchProperties properties;

    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .modelName(properties.getModelName())
                .dimensions(properties.getDimensions())
                .baseUrl(properties.getBaseUrl())
                .maxSegmentsPerBatch(9)
                .apiKey(properties.getApiKey()).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RestClient restClient() {
        return RestClient.builder(HttpHost.create(properties.getHost())).build();
    }

    @Bean(destroyMethod = "")
    public ElasticsearchClient knowledgeIndexClient(RestClient restClient) throws IOException {
        ElasticsearchClient client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        if (!client.indices().existsAlias(a -> a.name(INDEX_NAME)).value()) {
            client.indices().create(r -> r.index(FIRST_INDEX).aliases(INDEX_NAME, a -> a.isWriteIndex(true))
                    .settings(t -> t.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m
                            .meta("embedding_model", co.elastic.clients.json.JsonData.of(properties.getModelName()))
                            .properties("text", p -> p.text(t -> t.analyzer("standard")))
                            .properties("vector", p -> p.denseVector(v -> v.dims(properties.getDimensions())
                                    .similarity(DenseVectorSimilarity.Cosine).index(true)))
                            .properties("metadata", p -> p.object(o -> o.dynamic(co.elastic.clients.elasticsearch._types.mapping.DynamicMapping.False)
                                    .properties("docId", q -> q.long_(n -> n))
                                    .properties("versionId", q -> q.long_(n -> n))
                                    .properties("chunkId", q -> q.keyword(k -> k))
                                    .properties("parentChunkId", q -> q.keyword(k -> k))))));
        }
        var mappings = client.indices().getMapping(r -> r.index(INDEX_NAME)).result();
        if (mappings.size() != 1) throw new IllegalStateException("知识索引别名须指向一个物理索引");
        var mapping = mappings.values().iterator().next().mappings();
        var vector = mapping.properties().get("vector");
        var model = mapping.meta().get("embedding_model");
        if (vector == null || !vector.isDenseVector()
                || !Integer.valueOf(properties.getDimensions()).equals(vector.denseVector().dims())
                || model == null || !properties.getModelName().equals(model.to(String.class))) {
            throw new IllegalStateException("知识索引与当前向量模型不匹配，请重建后再切换别名");
        }
        return client;
    }
}
