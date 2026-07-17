package com.storeai.coach.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.coach.service.CoachAssistService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "AI 教练")
@RestController
@RequestMapping("/api/coach")
@RequiredArgsConstructor
public class CoachController {

    private final CoachAssistService coachAssistService;

    @PostMapping("/assist")
    public ApiResponse<Map<String, Object>> assist(@RequestBody AssistRequest req) {
        return ApiResponse.ok(coachAssistService.assist(req.question(), req.customerId(), req.meetingId()));
    }

    public record AssistRequest(String question, String customerId, String meetingId) {}
}
