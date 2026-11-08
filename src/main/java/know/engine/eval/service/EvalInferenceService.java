package know.engine.eval.service;

import know.engine.ai.entity.IntentRecognitionResult;
import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.entity.ChatMessage;
import know.engine.chat.entity.ChatParam;
import know.engine.chat.memory.DatabaseChatMemoryStore;
import know.engine.chat.service.ChatService;
import know.engine.chat.service.ChatConversationService;
import know.engine.chat.service.ChatMessageService;
import know.engine.eval.model.EvalCase;
import know.engine.eval.model.EvalCaseResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 单条评测用例推理：复用 RAG 管道，采集 answer 与 references
 */
@Service
@Slf4j
public class EvalInferenceService {

    // 评测专用用户 ID
    private static final String EVAL_USER_ID = "rag-eval";

    // 会话标题最大长度
    private static final int TITLE_MAX_LENGTH = 20;

    /**
     * 评测场景强制走 RAG，跳过意图识别 LLM 调用
     */
    private static final IntentRecognitionResult FORCE_RAG_INTENT = new IntentRecognitionResult("eval forced rag", true, "知识问答");

    // 注入聊天服务，RAG 对话入口
    @Autowired
    private ChatService chatService;

    // 注入会话服务，创建临时会话
    @Autowired
    private ChatConversationService chatConversationService;

    // 注入消息服务，读写聊天消息
    @Autowired
    private ChatMessageService chatMessageService;

    // 注入聊天记忆存储，清理评测临时会话的 Redis 缓存
    @Autowired
    private DatabaseChatMemoryStore databaseChatMemoryStore;

    // 对单条用例执行 RAG 推理
    public EvalCaseResult infer(EvalCase evalCase) {
        // 记录开始时间
        long startMs = System.currentTimeMillis();
        // 初始化结果对象
        EvalCaseResult result = new EvalCaseResult();
        // 写入用例 ID、问题
        result.setCaseId(evalCase.getId());
        result.setQuestion(evalCase.getQuestion());
        // 记录本用例的临时会话 ID，确保成功或失败后均可清理
        String conversationId = null;

        // 推理过程容错
        try {
            // 截取问题前缀作为临时会话标题
            String tempTitle = evalCase.getQuestion().substring(0, Math.min(evalCase.getQuestion().length(), TITLE_MAX_LENGTH));
            // 创建评测临时会话(随机会话ID)
            conversationId = chatConversationService.createConversation(EVAL_USER_ID, tempTitle);

            // 保存用户消息
            String messageId = chatMessageService.saveMessage(conversationId, ChatMessageType.USER, evalCase.getQuestion());
            // 预创建助手消息占位
            String assistantMessageId = chatMessageService.saveMessage(conversationId, ChatMessageType.ASSISTANT, null);

            // 构造强制 RAG 的聊天参数
            ChatParam chatParam = new ChatParam(EVAL_USER_ID, conversationId, messageId, evalCase.getQuestion(), assistantMessageId, FORCE_RAG_INTENT);

            // 收集流式答案 token、调用 RAG 聊天并阻塞至结束
            StringBuilder answerBuilder = new StringBuilder();
            chatService.ragChat(chatParam)
                    .doOnNext(event -> {
                        // 仅追加真正的答案 token
                        if (isAnswerToken(event)) {
                            // 拼接答案片段
                            answerBuilder.append(event);
                        }
                    })
                    .blockLast();

            // 读取助手消息（含引用）
            ChatMessage assistantMessage = chatMessageService.getByMessageId(assistantMessageId);
            // 读取用户消息（含转换内容）
            ChatMessage userMessage = chatMessageService.getByMessageId(messageId);

            // 写入拼接后的答案
            result.setAnswer(answerBuilder.toString());
            // 写入中间转换内容
            result.setTransformContent(userMessage != null ? userMessage.getTransformContent() : null);

            // 取出 RAG 引用，空则置空列表
            List<ChatMessage.RagReference> references = assistantMessage != null ? assistantMessage.getRagReferences() : List.of();
            // 防御性处理 null
            if (references == null) {
                // 统一为空列表
                references = List.of();
            }
            // 写入引用明细
            result.setRagReferences(references);
            // 提取分片 ID
            result.setRetrievedChunkIds(extractChunkIds(references));
            // 提取文档 ID
            result.setRetrievedDocumentIds(extractDocumentIds(references));
            // 标记成功
            result.setSuccess(true);
        } catch (Exception e) {
            log.error("评测推理失败: caseId={}, question={}", evalCase.getId(), evalCase.getQuestion(), e);
            // 标记失败、写入错误信息
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            // 评测结果已采集完成，删除复用聊天链路产生的临时数据
            cleanupTemporaryConversation(conversationId);
        }

        // 写入耗时
        result.setLatencyMs(System.currentTimeMillis() - startMs);
        return result;
    }

    // 清理当前评测用例的临时消息、会话及缓存；清理失败不影响评测结果
    private void cleanupTemporaryConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        try {
            chatMessageService.deleteMessagesByConversationId(conversationId);
        } catch (Exception e) {
            log.warn("清理评测临时消息失败: conversationId={}", conversationId, e);
        }

        try {
            chatConversationService.deleteConversation(conversationId);
        } catch (Exception e) {
            log.warn("清理评测临时会话失败: conversationId={}", conversationId, e);
        }

        try {
            databaseChatMemoryStore.evictCache(conversationId);
        } catch (Exception e) {
            log.warn("清理评测临时会话缓存失败: conversationId={}", conversationId, e);
        }
    }

    // 从引用中提取分片 ID
    private List<String> extractChunkIds(List<ChatMessage.RagReference> references) {
        // 空引用返回空列表
        if (CollectionUtils.isEmpty(references)) {
            // 无分片
            return List.of();
        }
        // 收集非空 chunkId
        List<String> chunkIds = new ArrayList<>();
        // 遍历引用
        for (ChatMessage.RagReference ref : references) {
            // 过滤空白 chunkId
            if (ref.getChunkId() != null && !ref.getChunkId().isBlank()) {
                // 加入列表
                chunkIds.add(ref.getChunkId());
            }
        }
        // 返回分片 ID 列表
        return chunkIds;
    }

    // 从引用中提取去重后的文档 ID
    private List<String> extractDocumentIds(List<ChatMessage.RagReference> references) {
        // 空引用返回空列表
        if (CollectionUtils.isEmpty(references)) {
            // 无文档
            return List.of();
        }
        // 流式映射、过滤、去重
        return references.stream()
                .map(ChatMessage.RagReference::getDocumentId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    // 判断流式事件是否为答案 token（过滤进度/引用等控制事件）
    private boolean isAnswerToken(String event) {
        // 空事件不是答案
        if (event == null || event.isEmpty()) {
            // 忽略
            return false;
        }
        // 排除进度、引用、卡片、结束等控制前缀
        return !event.startsWith("[PROGRESS]")
                && !event.startsWith("[REFERENCE]")
                && !event.startsWith("[CARD_")
                && !event.startsWith("[DONE]");
    }
}
