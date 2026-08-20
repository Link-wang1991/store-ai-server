package com.storeai.notification.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.notification.service.AnnouncementService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "通知公告")
@RestController
@RequestMapping("/api/admin/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(announcementService.list());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody AnnouncementService.CreateInput input) {
        return ApiResponse.ok(announcementService.create(input));
    }

    @PostMapping("/{id}/deactivate")
    public ApiResponse<Void> deactivate(@PathVariable String id) {
        announcementService.deactivate(id);
        return ApiResponse.ok();
    }
}
