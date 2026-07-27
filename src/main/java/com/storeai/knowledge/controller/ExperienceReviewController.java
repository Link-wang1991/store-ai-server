package com.storeai.knowledge.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.knowledge.service.ExperienceReviewService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;

@Tag(name = "经验审核")
@RestController
@RequestMapping("/api/experience-reviews")
@RequiredArgsConstructor
public class ExperienceReviewController {

    private final ExperienceReviewService experienceReviewService;

    @GetMapping
    public ApiResponse<List<ExperienceReviewService.ReviewItem>> listPending() {
        return ApiResponse.ok(experienceReviewService.listPending());
    }

    /** 员工从会谈详情提交候选；提交后仅进入审核队列。 */
    @PostMapping("/submit")
    public ApiResponse<Map<String, Object>> submit(@RequestBody SubmitRequest req) {
        return ApiResponse.ok(experienceReviewService.submit(
            req.meetingId(), req.title(), req.content(), req.category()));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable String id,
                                                     @RequestBody ApproveRequest req) {
        return ApiResponse.ok(experienceReviewService.approve(
            id, req.title(), req.category(), req.content(), req.visibleRoles()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable String id,
                                                    @RequestBody RejectRequest req) {
        return ApiResponse.ok(experienceReviewService.reject(id, req.reason()));
    }

    public record SubmitRequest(String meetingId, String title, String content, String category) {}
    public record ApproveRequest(String title, String category, String content, List<String> visibleRoles) {}
    public record RejectRequest(String reason) {}
}
