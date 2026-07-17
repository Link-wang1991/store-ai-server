package com.storeai.chat.controller;

import com.storeai.chat.service.ChatHistoryService;
import com.storeai.chat.service.ChatPipelineService;
import com.storeai.chat.service.ChatPipelineService.AnswerResult;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "AI 对话")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatPipelineService pipeline;
    private final ChatHistoryService historyService;

    @PostMapping
    public ApiResponse<AnswerResult> chat(@RequestBody ChatRequest req) {
        return ApiResponse.ok(pipeline.answer(
                req.question(), req.sessionId(), req.customerId()));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<ChatHistoryService.SessionItem>> sessions() {
        return ApiResponse.ok(historyService.listSessions());
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatHistoryService.ChatMessageItem>> messages(
            @PathVariable String sessionId) {
        return ApiResponse.ok(historyService.listMessages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        historyService.deleteSession(sessionId);
        return ApiResponse.ok();
    }

    public record ChatRequest(String question, String sessionId, String customerId) {}
}
