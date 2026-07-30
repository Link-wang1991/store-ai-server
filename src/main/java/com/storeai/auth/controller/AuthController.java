package com.storeai.auth.controller;

import com.storeai.auth.dto.LoginRequest;
import com.storeai.auth.dto.LoginResponse;
import com.storeai.auth.dto.RegisterRequest;
import com.storeai.auth.dto.RegisterResponse;
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

    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req));
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
