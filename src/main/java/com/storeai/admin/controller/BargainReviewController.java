package com.storeai.admin.controller;

import com.storeai.admin.service.BargainReviewService;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "议价复盘")
@RestController
@RequestMapping("/api/admin/bargain-reviews")
@RequiredArgsConstructor
public class BargainReviewController {

    private final BargainReviewService bargainReviewService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(bargainReviewService.list());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(bargainReviewService.summary());
    }
}
