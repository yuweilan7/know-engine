package know.engine.rag;

import know.engine.document.service.SegmentService;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.elasticsearch.ElasticsearchContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.*;
import dev.langchain4j.store.embedding.filter.Filter;
import org.elasticsearch.client.RestClient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static know.engine.rag.constant.MetadataKeyConstant.PARENT_CHUNK_ID;
import static java.util.stream.Collectors.toList;

/**
 * KnowEngine Elasticsearch 内容检索器
 * 基于 Elasticsearch 的向量检索实现，支持以下特性：
 *      向量检索 (KNN)：使用 Embedding 模型将查询文本向量化，进行相似度搜索
 *      全文检索：支持 Elasticsearch 全文搜索，扩展了默认检索不支持的权限过滤功能
 *      混合检索：结合向量检索和全文检索（需 Elasticsearch 相应许可证）
 *      关联内容扩展：根据 parentChunkId 从 Redis 读取父分段完整文本，替换子分段以获得更完整的语义
 *
 * @see ElasticsearchContentRetriever
 * @see ContentRetriever
 */
public class KnowEngineElasticsearchContentRetriever extends AbstractElasticsearchEmbeddingStore implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchContentRetriever.class);
    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final double minScore;
    private final Filter filter;
    private final SegmentService segmentService;

    /**
     * 构造检索器
     *
     * @param configuration  检索模式：Knn / FullText / Hybrid。缺省走 Builder 里的 Knn
     * @param restClient     ES 低级 REST 客户端，必填，通常全项目共享一个
     * @param indexName      知识片段所在索引；Builder 缺省为 {@code "default"}，不存在时父类会自动建索引
     * @param embeddingModel 查询文本转向量；KNN / 混合检索必用，全文检索不会调它
     * @param maxResults     本路最多召回条数（ChatService 里两路都是 5）
     * @param minScore       相似度下限，低于则丢弃；全文检索主要看 ES 自身打分
     * @param filter         LangChain4j 元数据过滤，会编进 KNN/混合的 EmbeddingSearchRequest；可为 null
     * @param segmentService 命中后按 parentChunkId 读父分段完整文本，替换子分段以补全语义
     */
    public KnowEngineElasticsearchContentRetriever(ElasticsearchConfiguration configuration,
                                                   RestClient restClient,
                                                   String indexName,
                                                   EmbeddingModel embeddingModel,
                                                   final int maxResults,
                                                   final double minScore,
                                                   final Filter filter,
                                                   SegmentService segmentService) {
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.filter = filter;
        this.segmentService = segmentService;
        this.initialize(configuration, restClient, indexName);
    }

    private List<TextSegment> toTextList(SearchResponse<Document> response) {
        return response.hits().hits().stream()
                .map(hit -> Optional.ofNullable(hit.source())
                        .map(document -> document.getText() == null
                                ? null
                                : TextSegment.from(
                                document.getText(),
                                new Metadata(document.getMetadata())
                                        .put(ContentMetadata.SCORE.name(), hit.score())
                                        .put(ContentMetadata.EMBEDDING_ID.name(), hit.id())))
                        .orElse(null))
                .collect(toList());
    }

    /**
     * 根据查询条件检索相关内容
     * 根据当前配置的检索模式执行内容检索，支持以下三种模式：
     * 全文检索：当配置为 {@link ElasticsearchConfigurationFullText} 时，直接执行全文搜索
     * 混合检索：当配置为 {@link ElasticsearchConfigurationHybrid} 时，结合向量检索和全文检索
     * 向量检索（默认）：将查询文本向量化后进行 KNN 相似度搜索
     * 检索结果还会进行父分段替换：根据 parentChunkId 从 Redis 中读取父分段的完整文本，替换子分段以获得更完整的语义
     *
     * @param query 查询对象，包含待检索的文本内容
     * @return 检索到的内容列表，包含原始检索结果及扩展的关联内容
     */
    @Override
    public List<Content> retrieve(final Query query) {
        List<Content> searchContents;
        // 全文检索不需要查询向量，避免一次无效的 Embedding 模型调用
        if (configuration instanceof ElasticsearchConfigurationFullText) {
            log.debug("Using a full text search query");
            searchContents = doFullTextQuery(query);
        } else {
            // KNN 和混合检索需要先将查询文本转换为向量
            Embedding referenceEmbedding = embeddingModel.embed(query.text()).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(referenceEmbedding)
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .filter(filter)
                    .build();

            if (configuration instanceof ElasticsearchConfigurationHybrid) {
                // 混合检索模式：结合向量检索和全文检索
                searchContents = mapResultsToContentList(this.hybridSearch(request, query.text()));
            } else {
                searchContents = mapResultsToContentList(this.search(request));
            }
        }

        return proccessParentContent(searchContents);
    }

    @NotNull
    private List<Content> proccessParentContent(List<Content> searchContents) {
        // 去重并按文本内容排序
        searchContents = searchContents.stream().distinct().sorted(Comparator.comparing(content -> content.textSegment().text())).toList();
        List<Content> finalContents = new ArrayList<>(searchContents);

        // 父分段缓存，避免重复查询
        Map<String, List<Content>> parentDocMap = new HashMap<>();

        for (Content content : searchContents) {
            // 父分段替换：用父分段的完整文本替换子分段，获取更完整的语义
            String parentChunkId = content.textSegment().metadata().getString(PARENT_CHUNK_ID);
            if (parentChunkId != null) {
                List<Content> cachedParentDocs = parentDocMap.get(parentChunkId);
                if (cachedParentDocs != null) {
                    // 如果已经缓存中有过这个父分段了，说明已经用过了，这里就不用再加了，避免重复
                    finalContents.remove(content);
                } else if (segmentService != null) {
                    // 读取 parentChunk 的文本内容
                    String segmentText = segmentService.getTextByChunkId(parentChunkId);
                    if (segmentText != null) {
                        // 用父分段文本构造新的 Content，替换当前的子分段内容
                        TextSegment parentSegment = TextSegment.from(segmentText, content.textSegment().metadata());
                        Content parentContent = Content.from(parentSegment, content.metadata());
                        List<Content> parentDocs = List.of(parentContent);
                        parentDocMap.put(parentChunkId, parentDocs);
                        finalContents.remove(content);
                        finalContents.addAll(parentDocs);
                    } else {
                        log.warn("parentChunk not found in Redis, chunkId: {}", parentChunkId);
                        finalContents.remove(content);
                    }
                }
            }
        }

        finalContents = finalContents.stream().sorted(new Comparator<Content>() {
            @Override
            public int compare(Content content1, Content content2) {
                return Double.compare((double) content2.metadata().get(ContentMetadata.SCORE), (double) content1.metadata().get(ContentMetadata.SCORE));
            }
        }).collect(Collectors.toList());
        return finalContents;
    }

    /**
     * 执行全文检索查询。默认的全文搜索不支持filter，所以需要定制
     * 使用 match 查询对 text 字段进行全文匹配，
     * 查询结果转换为 Content 列表，携带 SCORE 和 EMBEDDING_ID 元数据
     *
     * @param query 查询对象，包含检索文本
     * @return 带元数据的 Content 列表
     */
    @NotNull
    private List<Content> doFullTextQuery(Query query) {
        try {
            SearchResponse<Document> response = client.search(
                    s -> s.index(indexName)
                            .size(maxResults)
                            .query(q -> q.match(m -> m.field("text").query(query.text()))),
                    Document.class);

            // 将 ES 响应转换为 TextSegment 列表
            List<TextSegment> results = toTextList(response);
            // 将 TextSegment 转换为 Content，携带 SCORE 和 EMBEDDING_ID 元数据
            return results.stream()
                    .map(t -> Content.from(
                            t,
                            Map.of(
                                    ContentMetadata.SCORE, t.metadata().getDouble(ContentMetadata.SCORE.name()),
                                    ContentMetadata.EMBEDDING_ID,
                                    t.metadata().getString(ContentMetadata.EMBEDDING_ID.name()))))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Content> mapResultsToContentList(EmbeddingSearchResult<TextSegment> searchResult) {
        List<Content> result = searchResult.matches().stream()
                .filter(f -> f.score() > minScore)
                .map(m -> Content.from(
                        m.embedded(),
                        Map.of(
                                ContentMetadata.SCORE, m.score(),
                                ContentMetadata.EMBEDDING_ID, m.embeddingId())))
                .toList();
        log.debug("Found [{}] relevant documents in Elasticsearch index [{}].", result.size(), indexName);
        return result;
    }

    public static KnowEngineElasticsearchContentRetriever.Builder builder() {
        return new KnowEngineElasticsearchContentRetriever.Builder();
    }

    public static class Builder {

        private RestClient restClient;
        private String indexName = "default";
        private ElasticsearchConfiguration configuration =
                ElasticsearchConfigurationKnn.builder().build();
        private EmbeddingModel embeddingModel;
        private int maxResults;
        private double minScore;
        private Filter filter;
        private SegmentService segmentService;

        /**
         * @param restClient Elasticsearch RestClient.
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder restClient(RestClient restClient) {
            this.restClient = restClient;
            return this;
        }

        /**
         * @param indexName Elasticsearch index name (optional). Default value: "default".
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        /**
         * @param configuration the configuration to use
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder configuration(ElasticsearchConfiguration configuration) {
            this.configuration = configuration;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder filter(Filter filter) {
            this.filter = filter;
            return this;
        }


        public KnowEngineElasticsearchContentRetriever.Builder knowledgeSegmentService(SegmentService segmentService) {
            this.segmentService = segmentService;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever build() {
            return new KnowEngineElasticsearchContentRetriever(
                    configuration, restClient, indexName, embeddingModel, maxResults, minScore, filter, segmentService);
        }
    }
}