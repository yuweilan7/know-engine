package know.engine.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import know.engine.eval.entity.EvalRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
// 评测批次表 CRUD 接口，无自定义 SQL，沿用 BaseMapper
public interface EvalRunMapper extends BaseMapper<EvalRun> {
}
