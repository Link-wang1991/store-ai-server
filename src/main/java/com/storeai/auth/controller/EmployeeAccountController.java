package com.storeai.auth.controller;

import com.storeai.auth.service.EmployeeAccountService;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "员工账号")
@RestController
@RequestMapping("/api/admin/employees")
@RequiredArgsConstructor
public class EmployeeAccountController {

    private final EmployeeAccountService employeeAccountService;

    @PostMapping
    public ApiResponse<EmployeeAccountService.AccountView> create(@Valid @RequestBody CreateEmployeeRequest request) {
        return ApiResponse.ok(employeeAccountService.create(new EmployeeAccountService.CreateInput(
            request.name(), request.email(), request.password(), request.phone(), request.role()
        )));
    }

    @GetMapping("/switchable")
    public ApiResponse<List<EmployeeAccountService.AccountView>> switchable() {
        return ApiResponse.ok(employeeAccountService.listSwitchable());
    }

    /** 全店员工列表（管理端 CRUD）。 */
    @GetMapping("/all")
    public ApiResponse<List<Map<String, Object>>> all() {
        return ApiResponse.ok(employeeAccountService.listAll());
    }

    /** 停用员工账号。 */
    @PostMapping("/{employeeId}/deactivate")
    public ApiResponse<Void> deactivate(@PathVariable String employeeId) {
        employeeAccountService.deactivate(employeeId);
        return ApiResponse.ok();
    }

    @PostMapping("/{employeeId}/preview-login")
    public ApiResponse<EmployeeAccountService.PreviewLogin> previewLogin(@PathVariable String employeeId) {
        return ApiResponse.ok(employeeAccountService.previewLogin(employeeId));
    }

    public record CreateEmployeeRequest(
        @NotBlank(message = "请填写姓名") String name,
        @NotBlank(message = "请填写登录邮箱") @Email(message = "登录邮箱格式不正确") String email,
        @NotBlank(message = "请填写初始密码") @Size(min = 6, message = "初始密码至少 6 位") String password,
        String phone,
        @NotBlank(message = "请选择岗位") String role
    ) {}
}
