package com.storeai.admin.controller;

import com.storeai.admin.service.OperationsMonitoringService;
import com.storeai.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/operations")
@RequiredArgsConstructor
public class OperationsMonitoringController {
    private final OperationsMonitoringService operationsMonitoringService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(operationsMonitoringService.overview());
    }
}
