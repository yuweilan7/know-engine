package know.engine.document.constant;

public enum SplitType {

    /**
     * 按长度切分
     */
    LENGTH,

    /**
     * 按标题切分
     */
    TITLE,

    /**
     * 按正则切分
     */
    REGEX,

    /**
     * 智能切分
     */
    SMART,

    /**
     * 按分隔符切分
     */
    SEPARATOR,

    /** 表格固定使用表头和数据行构造切片。 */
    TABLE_ROWS
}
