package know.engine.document.util;

import know.engine.document.constant.FileType;
import know.engine.document.constant.SplitType;
import know.engine.document.entity.DocumentSplitParam;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 上传和旧版补交构建使用同一套默认值。队列记录的是解析后的参数。 */
public final class DocumentSplitPolicy {
    private DocumentSplitPolicy() {}

    public static DocumentSplitParam resolve(FileType fileType, DocumentSplitParam requested) {
        DocumentSplitParam defaults = switch (fileType) {
            case PDF, DOC -> new DocumentSplitParam("TITLE", 900, 120, 2, null, null);
            case MARKDOWN -> new DocumentSplitParam("TITLE", 900, 120, 3, null, null);
            case TXT -> new DocumentSplitParam("LENGTH", 600, 80, null, null, null);
            case CSV, EXCEL -> new DocumentSplitParam("TABLE_ROWS", null, null, null, null, null);
        };
        if (requested == null || requested.splitType() == null || requested.splitType().isBlank()) return defaults;
        SplitType type;
        try {
            type = SplitType.valueOf(requested.splitType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知切片方式");
        }
        if (fileType == FileType.CSV || fileType == FileType.EXCEL) {
            if (type != SplitType.TABLE_ROWS) throw new IllegalArgumentException("表格文件固定按表头和数据行切片");
            return defaults;
        }
        if (type == SplitType.TABLE_ROWS) throw new IllegalArgumentException("只有表格文件支持按行切片");

        int size = requested.chunkSize() == null ? defaults.chunkSize() : requested.chunkSize();
        if (size <= 0 || size > 100000) throw new IllegalArgumentException("切片大小须在 1 到 100000 之间");
        int overlap = type == SplitType.SMART ? size / 10
                : requested.overlap() == null ? Math.min(defaults.overlap(), size / 6) : requested.overlap();
        if (overlap < 0 || overlap >= size) throw new IllegalArgumentException("重叠长度须小于切片大小");

        Integer titleLevel = null;
        if (type == SplitType.TITLE) {
            titleLevel = requested.titleLevel() != null ? requested.titleLevel()
                    : defaults.titleLevel() == null ? 3 : defaults.titleLevel();
            if (titleLevel < 1 || titleLevel > 6) throw new IllegalArgumentException("标题级数须在 1 到 6 之间");
        }
        String separator = type == SplitType.SEPARATOR ? validPattern(requested.separator(), "分隔符表达式") : null;
        String regex = type == SplitType.REGEX ? validPattern(requested.regex(), "切片正则表达式") : null;
        return new DocumentSplitParam(type.name(), size, overlap, titleLevel, separator, regex);
    }

    private static String validPattern(String value, String label) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("请输入" + label);
        try {
            Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(label + "无效");
        }
        return value;
    }
}
