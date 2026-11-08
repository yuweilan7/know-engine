package know.engine.rag;

import know.engine.chat.constant.RetrievalSource;
import know.engine.rag.util.RetrievalSourceUtil;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 带进度通知的检索器
 * 在委托执行 {@link ContentRetriever#retrieve} 前发送进度通知，
 * 用于流式返回前端当前处理阶段，减少用户等待焦虑。
 *
 * @see ContentAggregator
 */
public class ProgressAwareContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final Consumer<String> progressCallback;
    private final RetrievalSource retrievalSource;

    /**
     * 确保检索进度只发送一次
     */
    private final AtomicBoolean progressSent = new AtomicBoolean(false);

    public ProgressAwareContentRetriever(ContentRetriever delegate, Consumer<String> progressCallback) {
        this(delegate, progressCallback, null);
    }

    public ProgressAwareContentRetriever(ContentRetriever delegate, Consumer<String> progressCallback, RetrievalSource retrievalSource) {
        this.delegate = delegate;
        this.progressCallback = progressCallback;
        this.retrievalSource = retrievalSource;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (progressCallback != null && progressSent.compareAndSet(false, true)) {
            progressCallback.accept("[PROGRESS]:正在检索知识库内容...");
        }
        List<Content> contents = delegate.retrieve(query);
        if (retrievalSource == null) {
            return contents;
        }
        return RetrievalSourceUtil.tagContents(contents, retrievalSource);
    }

    public ContentRetriever getDelegate() {
        return delegate;
    }
}
