package com.storeai.knowledge.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.knowledge.service.ExperienceReviewService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "经验审核")
@RestController
@RequestMapping("/api/experience-reviews")
@RequiredArgsConstructor
public class ExperienceReviewController {

    private final ExperienceReviewService experienceReviewService;

    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable String id,
                                                     @RequestBody ApproveRequest req) {
        return ApiResponse.ok(experienceReviewService.approve(id, req.title(), req.category()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable String id,
                                                    @RequestBody RejectRequest req) {
        return ApiResponse.ok(experienceReviewService.reject(id, req.reason()));
    }

    public record ApproveRequest(String title, String category) {}
    public record RejectRequest(String reason) {}
}
