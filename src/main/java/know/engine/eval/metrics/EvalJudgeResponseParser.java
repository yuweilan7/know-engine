package know.engine.eval.metrics;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 LLM-as-Judge 返回的 JSON 评分
 */
@Component
public class EvalJudgeResponseParser {

    // 匹配含 score 字段的简单 JSON 块
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("\\{[^{}]*\"score\"[^{}]*}", Pattern.DOTALL);

    // 将原始响应解析为 JudgeScore
    public JudgeScore parse(String rawResponse) {
        // 空响应直接失败
        if (rawResponse == null || rawResponse.isBlank()) {
            // 抛出参数异常
            throw new IllegalArgumentException("Judge 响应为空");
        }

        // 去掉首尾空白
        String trimmed = rawResponse.trim();
        // 优先按完整 JSON 解析
        try {
            // 直接解析整段文本
            return toJudgeScore(JSON.parseObject(trimmed));
        } catch (Exception ignored) {
            // 尝试从 markdown 代码块或混合文本中提取 JSON
        }

        // 用正则匹配含 score 的 JSON 片段
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(trimmed);
        // 若匹配成功则解析该片段
        if (matcher.find()) {
            // 解析匹配到的 JSON
            return toJudgeScore(JSON.parseObject(matcher.group()));
        }

        // 回退：截取首个 { 到最后一个 }
        int start = trimmed.indexOf('{');
        // 定位最后一个右花括号
        int end = trimmed.lastIndexOf('}');
        // 区间合法则尝试解析子串
        if (start >= 0 && end > start) {
            // 解析截取的 JSON 子串
            return toJudgeScore(JSON.parseObject(trimmed.substring(start, end + 1)));
        }

        // 三种策略均失败
        throw new IllegalArgumentException("无法解析 Judge 响应: " + abbreviate(trimmed));
    }

    // 将 JSONObject 转为 JudgeScore，并裁剪分数到 [0,1]
    private JudgeScore toJudgeScore(JSONObject json) {
        // 缺少 score 字段则失败
        if (json == null || !json.containsKey("score")) {
            // 抛出参数异常
            throw new IllegalArgumentException("Judge 响应缺少 score 字段");
        }
        // 读取分数
        double score = json.getDoubleValue("score");
        // 将分数限制在 0~1
        score = Math.max(0.0, Math.min(1.0, score));
        // 读取理由
        String reason = json.getString("reason");
        // 构造结果对象
        return new JudgeScore(score, reason);
    }

    // 截断过长文本便于错误信息展示
    private String abbreviate(String text) {
        // 不超过 120 则原样返回，否则加省略号
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }

    @Data
    @AllArgsConstructor
    // Judge 单条评分结果
    public static class JudgeScore {
        // 分数（0~1）
        private double score;
        // 评分理由
        private String reason;
    }
}
