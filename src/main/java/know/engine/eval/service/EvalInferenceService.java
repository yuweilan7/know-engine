package know.engine.eval.service;

import know.engine.chat.entity.ChatMessage.RagReference;
import know.engine.chat.service.ChatService;
import know.engine.eval.model.EvalCase;
import know.engine.eval.model.EvalCaseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 单条评测强制走 RAG，直接采集线上同款上下文和片段 ID，供召回指标和生成评分使用。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvalInferenceService {
    private final ChatService chat;

    public EvalCaseResult infer(EvalCase evalCase) {
        long start = System.nanoTime();
        var result = new EvalCaseResult();
        result.setCaseId(evalCase.getId());
        result.setQuestion(evalCase.getQuestion());
        try {
            var inference = Objects.requireNonNull(chat.evaluate(
                    new ChatService.Request(evalCase.getQuestion(), ChatService.Mode.RAG), List.of()).block());
            var context = inference.context();
            // 文档 ID 去重供文档级指标使用。分块 ID 和父块 ID 保留，用于现有 ground truth 匹配。
            List<RagReference> references = context == null ? List.of() : context.references();
            result.setAnswer(inference.answer());
            result.setTransformContent(context == null ? null : context.query());
            result.setRagReferences(references);
            result.setRetrievedChunkIds(references.stream().map(RagReference::getChunkId).filter(Objects::nonNull).toList());
            result.setRetrievedDocumentIds(references.stream().map(RagReference::getDocumentId)
                    .filter(Objects::nonNull).distinct().toList());
            result.setSuccess(inference.status().equals("COMPLETED"));
            if (!result.isSuccess()) result.setError(inference.reasonCode());
        } catch (RuntimeException error) {
            log.warn("Evaluation inference failed caseId={} error={}", evalCase.getId(), error.getClass().getSimpleName());
            result.setSuccess(false);
            result.setError(error.getMessage());
        } finally {
            result.setLatencyMs(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
        return result;
    }
}
