package com.storeai.common.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.common.service.StoreConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 门店业务字典（知识分类、客户池等）的正式 API。 */
@Tag(name = "门店自定义配置")
@RestController
@RequestMapping("/api/store-config")
@RequiredArgsConstructor
public class StoreConfigController {

    private final StoreConfigService storeConfigService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(storeConfigService.list());
    }

    @PutMapping("/{category}")
    public ApiResponse<List<Map<String, Object>>> replaceCategory(
        @PathVariable String category,
        @Valid @RequestBody ReplaceCategoryRequest request
    ) {
        return ApiResponse.ok(storeConfigService.replaceCategory(category, request.items()));
    }

    public record ReplaceCategoryRequest(List<StoreConfigService.ConfigItem> items) {}
}
