package know.engine.common;

/**
 * 默认用户
 * 本项目聚焦知识引擎与 RAG 能力，不包含用户体系。接口中的 userId / uploadUser 均为可选入参，
 * 不传时回落到此处的默认值，如需要调试多用户场景时由调用方显式传参用户信息即可。
 */
public final class DefaultUser {

    /**
     * 默认用户ID，用于会话归属与聊天记忆隔离
     */
    public static final String ID = "1";

    /**
     * 默认用户名，用于文档上传人等展示字段
     */
    public static final String NAME = "匿名用户";

    private DefaultUser() {
    }

    /**
     * 入参为空时回落到默认用户ID
     */
    public static String userIdOrDefault(String userId) {
        return isBlank(userId) ? ID : userId;
    }

    /**
     * 入参为空时回落到默认用户名
     */
    public static String userNameOrDefault(String userName) {
        return isBlank(userName) ? NAME : userName;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
