package com.storeai.chat.controller;

import com.storeai.chat.service.ChatPipelineService;
import com.storeai.chat.service.ChatPipelineService.AnswerResult;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "AI 对话")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatPipelineService pipeline;

    @PostMapping
    public ApiResponse<AnswerResult> chat(@RequestBody ChatRequest req) {
        return ApiResponse.ok(pipeline.answer(
            req.question(), req.sessionId(), req.customerId()));
    }

    public record ChatRequest(String question, String sessionId, String customerId) {}
}
