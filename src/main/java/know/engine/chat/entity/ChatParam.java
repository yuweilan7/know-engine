package know.engine.chat.entity;

import know.engine.ai.entity.IntentRecognitionResult;

/**
 * 单轮问答上下文参数，在意图识别判定需要 RAG 后组装，贯穿检索改写、双路召回、重排与流式生成。
 * 由 {@link know.engine.chat.service.ChatService} 在路由到 RAG 时创建并向下传递。
 */
public record ChatParam(
        /** 当前用户 ID */
        String userId,

        /** 会话 ID，关联会话记忆与历史消息 */
        String conversationId,

        /** 本轮用户消息 ID，用于异步回写问题改写结果等 */
        String messageId,

        /** 用户原始问题内容 */
        String content,

        /** 预插入的助手消息 ID，流式生成结束后回写完整回答与 RAG 引用 */
        String assistantMessageId,

        /** 意图识别结果，含是否需要检索、意图标签与推理过程 */
        IntentRecognitionResult intentRecognitionResult) {
}
