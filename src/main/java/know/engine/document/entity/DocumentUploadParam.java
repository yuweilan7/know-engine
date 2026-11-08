package know.engine.document.entity;

import org.springframework.web.multipart.MultipartFile;

/**
 * @param file
 * @param title
 * @param description
 * @param version
 */
public record DocumentUploadParam(MultipartFile file, String title, String description, String version) {
}
