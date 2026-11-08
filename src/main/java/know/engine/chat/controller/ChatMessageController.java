package know.engine.chat.controller;

import know.engine.chat.entity.ChatMessage;
import know.engine.chat.service.ChatMessageService;
import know.engine.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 消息 Controller
 */
@RestController
@RequestMapping("/chat/message")
public class ChatMessageController {

    @Autowired
    private ChatMessageService chatMessageService;

    /**
     * 获取会话的消息列表
     *
     * @param conversationId 会话ID
     * @return 消息列表
     */
    @GetMapping("/list")
    public Result<List<ChatMessage>> getMessageList(@RequestParam String conversationId) {
        List<ChatMessage> messages = chatMessageService.getMessagesByConversationId(conversationId);
        return Result.ok(messages, messages.size());
    }
}
