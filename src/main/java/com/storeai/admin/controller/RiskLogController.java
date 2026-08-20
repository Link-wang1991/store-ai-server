package com.storeai.admin.controller;

import com.storeai.admin.service.RiskLogService;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "风险复盘")
@RestController
@RequestMapping("/api/admin/risk-logs")
@RequiredArgsConstructor
public class RiskLogController {

    private final RiskLogService riskLogService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(riskLogService.list(status));
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(riskLogService.summary());
    }

    @PostMapping("/{id}/handle")
    public ApiResponse<Map<String, Object>> handle(@PathVariable String id,
                                                    @RequestBody(required = false) HandleRequest req) {
        return ApiResponse.ok(riskLogService.handle(id, req == null ? null : req.resolution()));
    }

    public record HandleRequest(String resolution) {}
}
