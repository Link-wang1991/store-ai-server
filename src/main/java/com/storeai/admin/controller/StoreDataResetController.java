package com.storeai.admin.controller;

import com.storeai.admin.service.StoreDataResetService;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "数据切换")
@RestController
@RequestMapping("/api/admin/data-reset")
@RequiredArgsConstructor
public class StoreDataResetController {

    private final StoreDataResetService dataResetService;

    @GetMapping("/preview")
    public ApiResponse<StoreDataResetService.Preview> preview() {
        return ApiResponse.ok(dataResetService.preview());
    }

    @PostMapping("/backup")
    public ApiResponse<StoreDataResetService.Backup> backup() {
        return ApiResponse.ok(dataResetService.backup());
    }

    @PostMapping("/clear")
    public ApiResponse<StoreDataResetService.ClearResult> clear(@Valid @RequestBody ClearRequest request) {
        return ApiResponse.ok(dataResetService.clear(request.confirmation()));
    }

    public record ClearRequest(@NotBlank String confirmation) {}
}
