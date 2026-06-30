package com.storeai.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.auth.dto.LoginRequest;
import com.storeai.auth.dto.LoginResponse;
import com.storeai.auth.entity.Employee;
import com.storeai.auth.entity.Store;
import com.storeai.auth.entity.User;
import com.storeai.auth.repository.EmployeeRepository;
import com.storeai.auth.repository.UserRepository;
import com.storeai.auth.security.JwtUtil;
import com.storeai.auth.security.UserDetailsImpl;
import com.storeai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public LoginResponse login(LoginRequest req) {
        // 查找用户
        var user = userRepository.selectOne(
            new LambdaQueryWrapper<User>()
                .eq(User::getEmail, req.getEmail().toLowerCase().trim()));
        if (user == null) {
            throw BizException.badRequest("邮箱或密码错误");
        }

        // 验证密码
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw BizException.badRequest("邮箱或密码错误");
        }

        // 查找关联员工（user_id 是 UUID 类型，需要显式转换）
        var employee = employeeRepository.selectOne(
            new LambdaQueryWrapper<Employee>().apply("user_id::text = {0}", user.getId()));
        if (employee == null) {
            throw BizException.badRequest("未找到关联的员工信息，请联系管理员");
        }
        if (!"active".equals(employee.getStatus())) {
            throw BizException.badRequest("账号已停用");
        }

        // 生成 JWT
        var details = UserDetailsImpl.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .password(user.getPasswordHash())
                .storeId(employee.getStoreId())
                .employeeId(employee.getId())
                .role(employee.getRole())
                .roleLabel(employee.getRole())
                .build();

        String token = jwtUtil.generateToken(details, Map.of(
                "storeId", employee.getStoreId(),
                "employeeId", employee.getId(),
                "role", employee.getRole()
        ));

        return new LoginResponse(token,
                user.getId(), employee.getId(),
                employee.getStoreId(), employee.getRole(),
                employee.getRole(), null);
    }
}
