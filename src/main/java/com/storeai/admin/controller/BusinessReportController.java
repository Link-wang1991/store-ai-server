package com.storeai.admin.controller;

import com.storeai.admin.service.BusinessReportService;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "经营报告（增长复盘）")
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class BusinessReportController {

    private final BusinessReportService businessReportService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(businessReportService.list());
    }

    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestParam(required = false) String type) {
        return ApiResponse.ok(businessReportService.generate(type));
    }
}
