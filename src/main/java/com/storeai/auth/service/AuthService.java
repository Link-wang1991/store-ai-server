package com.storeai.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.auth.dto.LoginRequest;
import com.storeai.auth.dto.LoginResponse;
import com.storeai.auth.dto.RegisterRequest;
import com.storeai.auth.dto.RegisterResponse;
import com.storeai.auth.entity.Employee;
import com.storeai.auth.entity.Store;
import com.storeai.auth.entity.User;
import com.storeai.auth.repository.EmployeeRepository;
import com.storeai.auth.repository.StoreRepository;
import com.storeai.auth.repository.UserRepository;
import com.storeai.auth.security.JwtUtil;
import com.storeai.auth.security.UserDetailsImpl;
import com.storeai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;
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

        // 查找关联员工
        var employee = employeeRepository.selectOne(
            new LambdaQueryWrapper<Employee>().apply("user_id = {0}", user.getId()));
        if (employee == null) {
            throw BizException.badRequest("未找到关联的员工信息，请联系管理员");
        }
        if (!"active".equals(employee.getStatus())) {
            throw BizException.badRequest("账号已停用");
        }

        // 查找门店
        var store = storeRepository.selectById(employee.getStoreId());
        String storeName = store != null ? store.getName() : "";

        // 生成 JWT（含 name / storeName）
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
                "role", employee.getRole(),
                "name", user.getName() != null ? user.getName() : "",
                "storeName", storeName
        ));

        return new LoginResponse(token,
                user.getId(), employee.getId(),
                employee.getStoreId(), employee.getRole(),
                employee.getRole(), storeName,
                user.getName() != null ? user.getName() : "");
    }

    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        String email = req.getEmail().toLowerCase().trim();

        // 检查邮箱是否已注册
        var existing = userRepository.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (existing != null) {
            throw BizException.badRequest("该邮箱已被注册");
        }

        // 创建用户
        var user = new User();
        user.setEmail(email);
        user.setName(req.getName());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setCreatedAt(OffsetDateTime.now());
        userRepository.insert(user);

        // 创建门店
        String storeName = req.getStoreName();
        if (storeName == null || storeName.isBlank()) {
            storeName = req.getName() + "的门店";
        }
        var store = new Store();
        store.setName(storeName);
        store.setOwnerId(user.getId());
        store.setCreatedAt(OffsetDateTime.now());
        store.setUpdatedAt(OffsetDateTime.now());
        storeRepository.insert(store);

        // 创建员工记录（owner 角色）
        var employee = new Employee();
        employee.setStoreId(store.getId());
        employee.setUserId(user.getId());
        employee.setName(req.getName());
        employee.setRole("owner");
        employee.setStatus("active");
        employee.setDataScope("store");
        employee.setCreatedAt(OffsetDateTime.now());
        employee.setUpdatedAt(OffsetDateTime.now());
        employeeRepository.insert(employee);

        // 生成 JWT（含 name / storeName）
        var details = UserDetailsImpl.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .password(user.getPasswordHash())
                .storeId(store.getId())
                .employeeId(employee.getId())
                .role("owner")
                .roleLabel("owner")
                .build();

        String token = jwtUtil.generateToken(details, Map.of(
                "storeId", store.getId(),
                "employeeId", employee.getId(),
                "role", "owner",
                "name", req.getName(),
                "storeName", storeName
        ));

        return new RegisterResponse(
                token, user.getId(), employee.getId(),
                store.getId(), "owner", "老板", storeName, req.getName());
    }
}
