package know.engine.document.service.impl;

import know.engine.document.constant.FileType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MinerU 文档处理：PDF / Word 统一走基类 ZIP→Markdown 转换流程。
 */
@Slf4j
@Service
public class MinerUFileProcessServiceImpl extends MinerUProcessBaseServiceImpl {

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.PDF || fileType == FileType.DOC;
    }
}
