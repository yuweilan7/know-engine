package know.engine.rag.reranker;

import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.DefaultContent;

import java.util.Objects;

public class KnowEngineDefaultContent extends DefaultContent {

    public KnowEngineDefaultContent(DefaultContent defaultContent) {
        super(defaultContent.textSegment(), defaultContent.metadata());
    }

    @Override
    public int hashCode() {
        return Objects.requireNonNull(this.metadata().get(ContentMetadata.EMBEDDING_ID)).hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return Objects.equals(this.metadata().get(ContentMetadata.EMBEDDING_ID), ((KnowEngineDefaultContent) o).metadata().get(ContentMetadata.EMBEDDING_ID));
    }

}
