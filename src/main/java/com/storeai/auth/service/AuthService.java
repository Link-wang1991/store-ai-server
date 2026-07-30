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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final long LOCAL_PREVIEW_TTL_MILLIS = 4 * 60 * 60 * 1000L;

    private static final Map<String, String> ROLE_LABELS = Map.of(
        "owner", "老板",
        "admin", "管理员",
        "manager", "店长",
        "consultant", "咨询师",
        "beautician", "美容师",
        "receptionist", "前台",
        "operator", "运营"
    );

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JdbcTemplate jdbc;

    @Value("${app.local-preview-login.enabled:false}")
    private boolean localPreviewLoginEnabled;

    @Value("${app.local-preview-login.owner-email:owner@demo.com}")
    private String localPreviewOwnerEmail;

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
                .roleLabel(roleLabel(employee.getRole()))
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
                roleLabel(employee.getRole()), storeName,
                user.getName() != null ? user.getName() : "");
    }

    /**
     * 本机验收专用：在 local profile 中由配置明确打开，列出同一门店的真实账号角色。
     * 不返回邮箱、密码或用户 ID，避免把账号信息当作免密接口的返回内容。
     */
    public List<LocalPreviewAccount> listLocalPreviewAccounts(HttpServletRequest request) {
        LocalPreviewStore previewStore = requireLocalPreviewStore(request);
        audit(previewStore.storeId(), null, "accounts_listed", request);
        return employeeRepository.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, previewStore.storeId())
                .eq(Employee::getStatus, "active")
                .isNotNull(Employee::getUserId)
                .orderByAsc(Employee::getCreatedAt))
            .stream()
            .map(employee -> new LocalPreviewAccount(
                employee.getId(),
                employee.getName() == null || employee.getName().isBlank() ? "未命名员工" : employee.getName(),
                employee.getRole(), roleLabel(employee.getRole()), entryFor(employee.getRole())
            ))
            .toList();
    }

    /**
     * 本机验收专用的短时角色令牌。只能进入配置 owner 所在门店的已启用员工，
     * 且仅在 local profile 明确开启时可调用，不校验也不会读取员工密码。
     */
    public LoginResponse localPreviewLogin(String employeeId, HttpServletRequest request) {
        if (employeeId == null || employeeId.isBlank()) throw BizException.badRequest("请选择要体验的角色");
        LocalPreviewStore previewStore = requireLocalPreviewStore(request);
        Employee employee = employeeRepository.selectById(employeeId);
        if (employee == null || !previewStore.storeId().equals(employee.getStoreId())
                || !"active".equals(employee.getStatus()) || employee.getUserId() == null) {
            audit(previewStore.storeId(), employeeId, "login_rejected", request);
            throw BizException.notFound("可体验的员工账号");
        }
        User user = userRepository.selectById(employee.getUserId());
        if (user == null) throw BizException.notFound("可体验的员工账号");
        Store store = storeRepository.selectById(employee.getStoreId());
        String storeName = store == null || store.getName() == null ? "" : store.getName();
        String name = user.getName() == null || user.getName().isBlank() ? employee.getName() : user.getName();

        UserDetailsImpl details = UserDetailsImpl.builder()
            .userId(user.getId())
            .email(user.getEmail())
            .password(user.getPasswordHash())
            .storeId(employee.getStoreId())
            .employeeId(employee.getId())
            .role(employee.getRole())
            .roleLabel(roleLabel(employee.getRole()))
            .build();
        String token = jwtUtil.generateToken(details, Map.of(
            "storeId", employee.getStoreId(),
            "employeeId", employee.getId(),
            "role", employee.getRole(),
            "name", name == null ? "" : name,
            "storeName", storeName,
            "localPreview", true
        ), LOCAL_PREVIEW_TTL_MILLIS);
        audit(previewStore.storeId(), employee.getId(), "login_issued", request);
        return new LoginResponse(token, user.getId(), employee.getId(), employee.getStoreId(),
            employee.getRole(), roleLabel(employee.getRole()), storeName, name == null ? "" : name);
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

    private static String roleLabel(String role) {
        return ROLE_LABELS.getOrDefault(role, role == null ? "" : role);
    }

    private LocalPreviewStore requireLocalPreviewStore(HttpServletRequest request) {
        if (!localPreviewLoginEnabled) throw BizException.notFound("本机角色体验");
        if (!isLoopbackRequest(request)) {
            audit(null, null, "remote_rejected", request);
            throw BizException.notFound("本机角色体验");
        }
        User owner = userRepository.selectOne(new LambdaQueryWrapper<User>()
            .eq(User::getEmail, localPreviewOwnerEmail.trim().toLowerCase()));
        if (owner == null) throw BizException.badRequest("未找到本机体验的老板账号，请先使用正常登录");
        Employee employee = employeeRepository.selectOne(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getUserId, owner.getId())
            .eq(Employee::getStatus, "active"));
        if (employee == null) throw BizException.badRequest("本机体验老板账号未启用");
        return new LocalPreviewStore(employee.getStoreId());
    }

    /**
     * 后端的免密接口只接受由本机 Next 同源代理转发的 loopback 请求。手机可访问
     * Next 页面，但不能绕过它直接调用 8080；公网/直连地址即便误开配置也会被拒绝。
     */
    private boolean isLoopbackRequest(HttpServletRequest request) {
        if (request == null) return false;
        try { return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress(); }
        catch (Exception ignored) { return false; }
    }

    private void audit(String storeId, String targetEmployeeId, String action, HttpServletRequest request) {
        try {
            jdbc.update("""
                INSERT INTO role_preview_audit
                (id, store_id, target_employee_id, action, request_ip, request_origin, user_agent, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                """, java.util.UUID.randomUUID().toString().replace("-", ""), storeId, targetEmployeeId, action,
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : trim(request.getHeader("X-Store-AI-Preview-Origin"), 255),
                request == null ? null : trim(request.getHeader("User-Agent"), 500));
        } catch (Exception ignored) {
            // 审计失败不得导致正式登录或其他业务受影响；本机体验本身仍由上方边界保护。
        }
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String entryFor(String role) {
        return "owner".equals(role) || "manager".equals(role) || "admin".equals(role) ? "/admin" : "/home";
    }

    public record LocalPreviewAccount(String employeeId, String name, String role, String roleLabel, String entry) {}
    private record LocalPreviewStore(String storeId) {}
}
