package know.engine.chat.controller;

import know.engine.chat.entity.ChatConversation;
import know.engine.chat.service.ChatConversationService;
import know.engine.chat.service.ChatMessageService;
import know.engine.common.DefaultUser;
import know.engine.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话 Controller
 */
@RestController
@RequestMapping("/chat/conversation")
public class ChatConversationController {

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    /**
     * 获取指定用户的会话列表
     *
     * @param userId 用户ID（可选，不传则使用默认用户）
     * @return 会话列表
     */
    @GetMapping("/list")
    public Result<List<ChatConversation>> getConversationList(@RequestParam(required = false) String userId) {
        List<ChatConversation> conversations = chatConversationService.getConversationsByUserId(DefaultUser.userIdOrDefault(userId));
        return Result.ok(conversations, conversations.size());
    }

    /**
     * 删除会话
     *
     * @param conversationId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/delete")
    public Result<Void> deleteConversation(@RequestParam String conversationId) {
        chatMessageService.deleteMessagesByConversationId(conversationId);
        boolean success = chatConversationService.deleteConversation(conversationId);
        return Result.result(success, success ? "会话已删除" : "删除失败");
    }
}
