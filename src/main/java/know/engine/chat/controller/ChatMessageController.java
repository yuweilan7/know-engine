package know.engine.chat.controller;

import know.engine.chat.service.ChatRunService;
import know.engine.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/chat/message")
@RequiredArgsConstructor
public class ChatMessageController {
    private final ChatRunService runs;

    @GetMapping("/list")
    public Result<List<ChatRunService.Message>> getMessageList(@RequestParam String conversationId,
                                                               @RequestParam(required = false) String userId) {
        var messages = runs.history(conversationId, userId);
        return Result.ok(messages, messages.size());
    }
}
