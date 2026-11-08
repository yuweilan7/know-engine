package know.engine.rag.util;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import know.engine.chat.constant.RetrievalSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static know.engine.rag.constant.MetadataKeyConstant.RETRIEVAL_SOURCE;

/**
 * 在 RAG 管道中追踪并解析 {@link RetrievalSource}。
 */
public final class RetrievalSourceUtil {

    private RetrievalSourceUtil() {
    }

    public static List<Content> tagContents(List<Content> contents, RetrievalSource source) {
        return contents.stream().map(content -> tagContent(content, source)).toList();
    }

    public static Content tagContent(Content content, RetrievalSource source) {
        Metadata metadata = new Metadata(content.textSegment().metadata().toMap());
        metadata.put(RETRIEVAL_SOURCE, source.name());
        TextSegment segment = TextSegment.from(content.textSegment().text(), metadata);
        return Content.from(segment, content.metadata());
    }

    public static RetrievalSource resolveFromContent(Content content) {
        String value = content.textSegment().metadata().getString(RETRIEVAL_SOURCE);
        if (value != null) {
            return RetrievalSource.valueOf(value);
        }
        if (content.metadata().get(ContentMetadata.RERANKED_SCORE) != null) {
            return RetrievalSource.RERANK;
        }
        return RetrievalSource.HYBRID;
    }

    public static RetrievalSource merge(RetrievalSource existing, RetrievalSource incoming) {
        if (existing == incoming) {
            return existing;
        }
        if (existing == RetrievalSource.HYBRID || incoming == RetrievalSource.HYBRID) {
            return RetrievalSource.HYBRID;
        }
        return RetrievalSource.HYBRID;
    }

    public static Content withMergedSource(Content content, RetrievalSource mergedSource) {
        return tagContent(content, mergedSource);
    }

    public static Map<ContentMetadata, Object> copyMetadataWithRerankScore(Content original, Double rerankScore) {
        Map<ContentMetadata, Object> metadata = new HashMap<>(original.metadata());
        metadata.put(ContentMetadata.RERANKED_SCORE, rerankScore);
        return metadata;
    }
}
