package know.engine.document.service;

import know.engine.document.constant.FileType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FileProcessServiceFactory {
    // 注入 MarkdownProcessServiceImpl、MinerUFileProcessServiceImpl 两个 Bean
    @Autowired
    private List<FileProcessService> fileProcessServiceList;

    public FileProcessService get(FileType fileProcessType) {
        return fileProcessServiceList.stream()
                .filter(service -> service.supports(fileProcessType))
                .findFirst().orElse(null);
    }
}
