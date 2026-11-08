package know.engine.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测集上传结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalDatasetUploadResponse {

    /**
     * 落盘后的绝对路径，可直接作为 datasetPath 传给 /eval/run
     */
    private String datasetPath;
}