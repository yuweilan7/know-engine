package know.engine.chat.constant;

/**
 * 聊天消息角色类型
 */
public enum ChatMessageType {

    /** 用户发送的消息 */
    USER,

    /** AI 助手回复的消息（创建时 content 可为 null，流式完成后回填） */
    ASSISTANT;
}
