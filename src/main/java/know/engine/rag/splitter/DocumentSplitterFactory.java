package know.engine.rag.splitter;

import know.engine.document.constant.SplitType;
import know.engine.document.entity.DocumentSplitParam;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;

public class DocumentSplitterFactory {

    public static DocumentSplitter getInstance(DocumentSplitParam documentSplitParam) {
        if (SplitType.LENGTH.name().equals(documentSplitParam.splitType())) {
            return new DocumentByWordSplitter(documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        if (SplitType.TITLE.name().equals(documentSplitParam.splitType())) {
            return new MarkdownHeaderParentTextSplitter(documentSplitParam.titleLevel(), false, false, documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        if (SplitType.REGEX.name().equals(documentSplitParam.splitType())) {
            return new DocumentByRegexSplitter(documentSplitParam.regex(), "\\n\\n", documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        if (SplitType.SMART.name().equals(documentSplitParam.splitType())) {
            return new MarkdownHeaderParentTextSplitter(documentSplitParam.chunkSize(), (int) (documentSplitParam.chunkSize() * 0.1));
        }

        if (SplitType.SEPARATOR.name().equals(documentSplitParam.splitType())) {
            return new DocumentByRegexSplitter(documentSplitParam.separator(), "\\n\\n", documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }
        return null;
    }

}
