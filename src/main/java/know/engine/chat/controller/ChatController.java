package know.engine.chat.controller;

import know.engine.chat.service.ChatService;
import know.engine.common.DefaultUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 流式对话接口
 */
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    @Autowired
    private ChatService chatService;

    /**
     * 流式对话接口
     * 入参：content（用户问题）、conversationId（会话ID [可选]）
     * 返回：SSE 流，每个 token 逐字推送
     * 进度通知格式： [PROGRESS]:xxx...  用于在前端展示当前处理阶段，减少等待焦虑
     * 推送环节包括：意图识别、问题改写、问题路由、排序筛选、生成回答等
     *
     * @param content        用户问题
     * @param conversationId 会话ID（可选，不传则自动创建新会话）
     * @param userId         用户ID（可选，不传则使用默认用户）
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> send(@RequestParam String content,
                             @RequestParam(required = false) String conversationId,
                             @RequestParam(required = false) String userId) {
        return chatService.chat(DefaultUser.userIdOrDefault(userId), content, conversationId);
    }
}
