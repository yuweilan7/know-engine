package know.engine.chat.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import know.engine.chat.entity.ChatMessage.RagReference;
import know.engine.chat.service.ChatRunService;
import know.engine.chat.service.ChatRunService.ChatEvent;
import know.engine.chat.service.ChatRunService.CreateRequest;
import know.engine.chat.service.ChatRunService.End;
import know.engine.chat.service.ChatService;
import know.engine.rag.RagContext.Prepared;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.UUID;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatRunService runs;
    private final ObjectMapper json;

    @PostMapping("/runs")
    public ChatRunService.Meta create(@RequestBody CreateRequest request) {
        return runs.create(request);
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEvent>> events(@PathVariable String runId, @RequestParam(required = false) String userId,
                                                 HttpServletResponse response) {
        sseHeaders(response);
        return runs.open(runId, userId).map(event -> event.type().equals("heartbeat")
                ? ServerSentEvent.<ChatEvent>builder().comment("heartbeat").build()
                : ServerSentEvent.builder(event).event(event.type()).id(runId + ":" + event.sequence()).build());
    }

    @PostMapping("/runs/{runId}/cancel")
    public ChatRunService.Cancellation cancel(@PathVariable String runId, @RequestParam(required = false) String userId) {
        return runs.cancel(runId, userId);
    }

    @GetMapping("/runs/{runId}")
    public ChatRunService.State state(@PathVariable String runId, @RequestParam(required = false) String userId) {
        return runs.state(runId, userId);
    }

    /** Deprecated transport adapter. Runs and persistence are shared with the named-event API. */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> send(@RequestParam String content, @RequestParam(required = false) String conversationId,
                                              @RequestParam(required = false) String userId, HttpServletResponse response) {
        sseHeaders(response);
        var run = runs.create(new CreateRequest(UUID.randomUUID().toString(), conversationId, content, ChatService.Mode.AUTO, userId));
        return runs.open(run.runId(), userId).handle((event, sink) -> {
            if (event.type().equals("heartbeat")) {
                sink.next(ServerSentEvent.<String>builder().comment("heartbeat").build());
                return;
            }
            String data = legacy(event, run.conversationId());
            if (data != null) sink.next(ServerSentEvent.builder(data).build());
        });
    }

    private String legacy(ChatEvent event, String conversationId) {
        return switch (event.type()) {
            case "progress" -> "[PROGRESS]:" + ((ChatService.Progress) event.payload()).message();
            case "warning" -> "[PROGRESS]:" + ((ChatService.Warning) event.payload()).message();
            case "references" -> {
                var refs = ((Prepared) event.payload()).references();
                // 旧协议发送文档卡片。完整子块及上下文映射保存在新协议和数据库中。
                var documents = new LinkedHashMap<String, RagReference>();
                refs.forEach(ref -> documents.putIfAbsent(ref.getDocumentId(), ref));
                try {
                    yield documents.isEmpty() ? null : "[REFERENCE]:" + json.writeValueAsString(documents.values());
                } catch (JsonProcessingException error) {
                    throw new IllegalStateException("Could not encode legacy references", error);
                }
            }
            case "delta" -> ((ChatService.Delta) event.payload()).text();
            case "end" -> {
                var end = (End) event.payload();
                yield end.status().equals("COMPLETED") ? "[DONE]:" + conversationId : "[WARN]:" + end.reasonCode();
            }
            case "error" -> "[WARN]:回答保存失败，请查询状态";
            default -> null;
        };
    }

    private static void sseHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
    }
}
