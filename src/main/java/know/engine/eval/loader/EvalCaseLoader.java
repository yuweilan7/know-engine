package know.engine.eval.loader;

import com.alibaba.fastjson2.JSON;
import jodd.util.StringUtil;
import know.engine.eval.model.EvalCase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 JSONL 文件加载评测用例
 */
@Component
public class EvalCaseLoader {

    // classpath 路径前缀
    private static final String CLASSPATH_PREFIX = "classpath:";

    // 按路径加载 JSONL 用例列表
    public List<EvalCase> load(String datasetPath) throws IOException {
        try (BufferedReader reader = openReader(datasetPath)) {
            // 收集解析后的用例
            List<EvalCase> cases = new ArrayList<>();
            // 当前行内容
            String line;
            // 行号
            int lineNo = 0;
            // 逐行读取
            while ((line = reader.readLine()) != null) {
                // 行号递增、去掉首尾空白、跳过空行与注释行
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // 解析为 EvalCase
                EvalCase evalCase = JSON.parseObject(trimmed, EvalCase.class);
                // question 必填, 缺少 question 则报错并带行号
                if (StringUtil.isBlank(evalCase.getQuestion())) {
                    throw new IllegalArgumentException("第 " + lineNo + " 行缺少 question 字段");
                }
                // 无 id 时按行号生成默认 ID
                if (StringUtil.isBlank(evalCase.getId())) {
                    evalCase.setId("case-" + lineNo);
                }
                cases.add(evalCase);
            }
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("测试集为空: " + datasetPath);
            }
            return cases;
        }
    }

    private BufferedReader openReader(String datasetPath) throws IOException {
        // classpath 资源路径
        if (datasetPath.startsWith(CLASSPATH_PREFIX)) {
            // 去掉前缀得到资源相对路径
            String resourcePath = datasetPath.substring(CLASSPATH_PREFIX.length());
            // 构造 ClassPathResource
            ClassPathResource resource = new ClassPathResource(resourcePath);
            // 资源必须存在
            if (!resource.exists()) {
                throw new IllegalArgumentException("classpath 资源不存在: " + resourcePath);
            }
            // UTF-8 读取资源流
            return new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
        }
        // 本地文件路径
        Path path = Path.of(datasetPath);
        // 文件必须存在
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在: " + datasetPath);
        }
        // UTF-8 读取本地文件
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }
}
