package com.storeai.auth.controller;

import com.storeai.auth.service.SuperAdminService;
import com.storeai.common.dto.ApiResponse;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "超级管理员")
@RestController
@RequestMapping("/api/super-admin")
@RequiredArgsConstructor
public class SuperAdminController {

    private final SuperAdminService superAdminService;
    private final CurrentUser currentUser;

    private void requireSuperAdmin() {
        if (!currentUser.isSuperAdmin()) throw BizException.forbidden("仅平台超级管理员可访问");
    }

    /** 门店列表（含基础统计）。 */
    @GetMapping("/stores")
    public ApiResponse<List<SuperAdminService.StoreSummary>> stores() {
        requireSuperAdmin();
        return ApiResponse.ok(superAdminService.listStores());
    }

    /** 创建门店并录入负责人（owner）账号。 */
    @PostMapping("/stores")
    public ApiResponse<SuperAdminService.StoreSummary> createStore(@Valid @RequestBody CreateStoreRequest req) {
        requireSuperAdmin();
        return ApiResponse.ok(superAdminService.createStore(new SuperAdminService.CreateStoreInput(
            req.name(), req.ownerName(), req.ownerPhone(), req.ownerPassword()
        )));
    }

    /** 初始化/补全指定门店的默认咨询场景与知识库。 */
    @PostMapping("/stores/{storeId}/init")
    public ApiResponse<Void> initStore(@PathVariable String storeId) {
        requireSuperAdmin();
        superAdminService.initStore(storeId);
        return ApiResponse.ok(null);
    }

    public record CreateStoreRequest(
        @NotBlank(message = "请填写门店名称") String name,
        @NotBlank(message = "请填写负责人姓名") String ownerName,
        @NotBlank(message = "请填写负责人手机号") @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String ownerPhone,
        @NotBlank(message = "请填写初始密码") @Size(min = 6, message = "初始密码至少 6 位") String ownerPassword
    ) {}
}
