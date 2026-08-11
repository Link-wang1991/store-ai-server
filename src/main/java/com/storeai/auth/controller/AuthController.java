package com.storeai.auth.controller;

import com.storeai.auth.dto.LoginRequest;
import com.storeai.auth.dto.LoginResponse;
import com.storeai.auth.dto.SendCodeRequest;
import com.storeai.auth.dto.SendCodeResponse;
import com.storeai.auth.dto.PhoneLoginRequest;
import com.storeai.auth.dto.WxBindRequest;
import com.storeai.auth.dto.WxLoginRequest;
import com.storeai.auth.dto.WxLoginResult;
import com.storeai.auth.service.AuthService;
import com.storeai.auth.entity.Employee;
import com.storeai.auth.entity.Store;
import com.storeai.auth.repository.EmployeeRepository;
import com.storeai.auth.repository.StoreRepository;
import com.storeai.common.dto.ApiResponse;
import com.storeai.common.util.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.List;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Map<String, String> ROLE_LABELS = Map.of(
        "owner", "老板",
        "admin", "管理员",
        "manager", "店长",
        "consultant", "咨询师",
        "beautician", "美容师",
        "receptionist", "前台",
        "operator", "运营"
    );

    private final AuthService authService;
    private final CurrentUser currentUser;
    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    /** 发送短信验证码（手机号登录 / 找回密码）。开发期 mock 模式会在响应回传 devCode。 */
    @PostMapping("/send-code")
    public ApiResponse<SendCodeResponse> sendCode(@Valid @RequestBody SendCodeRequest req) {
        return ApiResponse.ok(authService.sendCode(req));
    }

    /** 手机号 + 验证码登录。账号必须由超级管理员预录入，不开放自助注册。 */
    @PostMapping("/login-by-phone")
    public ApiResponse<LoginResponse> loginByPhone(@Valid @RequestBody PhoneLoginRequest req) {
        return ApiResponse.ok(authService.loginByPhone(req));
    }

    /** 微信一键登录：code → openid → 已绑定直接返回令牌；未绑定返回 needBind。 */
    @PostMapping("/wx-login")
    public ApiResponse<WxLoginResult> wxLogin(@Valid @RequestBody WxLoginRequest req) {
        return ApiResponse.ok(authService.wxLogin(req));
    }

    /** 微信登录后绑定手机号：code + 手机号 + 验证码 → 绑定 openid 并返回令牌。 */
    @PostMapping("/wx-bind")
    public ApiResponse<LoginResponse> wxBind(@Valid @RequestBody WxBindRequest req) {
        return ApiResponse.ok(authService.wxBindPhone(req));
    }

    /** 仅 local profile 开启的免密角色体验入口，见 application-local.yml。 */
    @GetMapping("/local-preview-accounts")
    public ApiResponse<List<AuthService.LocalPreviewAccount>> localPreviewAccounts(HttpServletRequest request) {
        return ApiResponse.ok(authService.listLocalPreviewAccounts(request));
    }

    /** 仅 local profile 开启的免密角色体验入口，签发四小时短时令牌。 */
    @PostMapping("/local-preview-login")
    public ApiResponse<LoginResponse> localPreviewLogin(@RequestBody LocalPreviewLoginRequest req, HttpServletRequest request) {
        return ApiResponse.ok(authService.localPreviewLogin(req.employeeId(), request));
    }

    /**
     * 由 Spring Security 校验 Bearer JWT 后返回当前身份。
     * Next.js 仅在该接口通过时才会把 cookie 当作有效会话使用。
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me() {
        Employee employee = employeeRepository.selectById(currentUser.employeeId());
        Store store = storeRepository.selectById(currentUser.storeId());
        return ApiResponse.ok(Map.of(
                "userId", currentUser.userId(),
                "employeeId", currentUser.employeeId(),
                "storeId", currentUser.storeId(),
                "role", currentUser.role(),
                "roleLabel", roleLabel(currentUser.role()),
                "email", currentUser.email() == null ? "" : currentUser.email(),
                "name", employee != null && employee.getName() != null ? employee.getName() : "",
                "storeName", store != null && store.getName() != null ? store.getName() : ""
        ));
    }

    private static String roleLabel(String role) {
        return ROLE_LABELS.getOrDefault(role, role == null ? "" : role);
    }

    public record LocalPreviewLoginRequest(String employeeId) {}
}
