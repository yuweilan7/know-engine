package know.engine.document.service;

import know.engine.document.constant.FileType;

import java.io.InputStream;

/** 解析器只负责生成文件。解析成功后，由文档流程记录对象键并推进阶段。 */
public interface FileProcessService {
    String processDocument(InputStream source, String originalName, String outputPrefix);
    boolean supports(FileType fileType);
}
