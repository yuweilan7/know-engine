package know.engine.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import know.engine.document.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * RAG 评测批次表
 */
@Getter
@Setter
@TableName("eval_run")
public class EvalRun extends BaseEntity {

    // 主键自增，数据库主键
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    // 业务侧运行 ID
    private String runId;

    // 数据集路径
    private String datasetPath;

    // 配置 JSON 字符串
    private String configJson;

    // 汇总快照 JSON 字符串
    private String summaryJson;

    // 用例总数
    private Integer totalCases;

    // 检索评测完成数
    private Integer evaluatedCases;

    // 生成评测完成数
    private Integer generationEvaluatedCases;

    // 批次状态（如 RUNNING/SUCCESS/FAILED）
    private String status;
}
