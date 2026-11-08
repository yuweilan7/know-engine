package know.engine.eval.model;

import lombok.Data;

/**
 * 触发批量评测的请求体
 */
@Data
public class EvalRunRequest {

    /**
     * 测试集路径，支持 classpath:eval/datasets/xxx.jsonl 或绝对文件路径
     */
    private String datasetPath;

    // 评测配置，默认使用内置默认值
    private EvalConfig config = new EvalConfig();
}
