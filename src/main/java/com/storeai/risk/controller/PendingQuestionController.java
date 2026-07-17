package com.storeai.risk.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.risk.service.PendingQuestionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "待处理问题")
@RestController
@RequestMapping("/api/pending-questions")
@RequiredArgsConstructor
public class PendingQuestionController {

    private final PendingQuestionService pendingQuestionService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(pendingQuestionService.list());
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<Map<String, Object>> assign(@PathVariable String id,
                                                    @RequestParam String assigneeId) {
        return ApiResponse.ok(pendingQuestionService.assign(id, assigneeId));
    }

    @PostMapping("/{id}/ack")
    public ApiResponse<Map<String, Object>> ack(@PathVariable String id) {
        return ApiResponse.ok(pendingQuestionService.ack(id));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<Map<String, Object>> resolve(@PathVariable String id,
                                                     @RequestBody ResolveRequest req) {
        return ApiResponse.ok(pendingQuestionService.resolve(id, req.reply()));
    }

    @PostMapping("/{id}/escalate")
    public ApiResponse<Map<String, Object>> escalate(@PathVariable String id,
                                                      @RequestBody EscalateRequest req) {
        return ApiResponse.ok(pendingQuestionService.escalate(id, req.reason()));
    }

    public record ResolveRequest(String reply) {}
    public record EscalateRequest(String reason) {}
}
