package know.engine.chat.service;

import know.engine.chat.entity.ChatConversation;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 会话表 Service 接口
 */
public interface ChatConversationService extends IService<ChatConversation> {

    /**
     * 根据用户ID获取会话列表
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    List<ChatConversation> getConversationsByUserId(String userId);

    /**
     * 创建新会话
     *
     * @param userId 用户ID
     * @param title  会话标题
     * @return 会话ID
     */
    String createConversation(String userId, String title);

    /**
     * 更新会话标题
     *
     * @param conversationId 会话ID
     * @param title          新标题
     * @return 是否成功
     */
    boolean updateTitle(String conversationId, String title);

    /**
     * 删除会话（逻辑删除，依赖 BaseEntity.deleted 字段）
     *
     * @param conversationId 会话ID
     * @return 是否成功
     */
    boolean deleteConversation(String conversationId);
}
