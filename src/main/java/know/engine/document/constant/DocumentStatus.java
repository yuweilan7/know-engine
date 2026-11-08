package know.engine.document.constant;

/** 版本内容已经完成的阶段。构建失败会在版本记录上保存错误和重试时间。 */
public enum DocumentStatus {
    UPLOADING, UPLOADED, CONVERTED, CHUNKED
}
