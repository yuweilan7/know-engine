package know.engine.document.entity;

import org.springframework.web.multipart.MultipartFile;

public record DocumentUploadParam(MultipartFile file, String title, String description, String version,
                                  DocumentSplitParam split) {
}
