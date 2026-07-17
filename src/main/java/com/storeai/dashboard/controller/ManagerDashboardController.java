package com.storeai.dashboard.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.dashboard.service.ManagerDashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "店长驾驶舱")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class ManagerDashboardController {

    private final ManagerDashboardService managerDashboardService;

    @GetMapping("/manager")
    public ApiResponse<Map<String, Object>> manager() {
        return ApiResponse.ok(managerDashboardService.buildDashboard());
    }
}
