package com.storeai.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.auth.entity.Employee;
import com.storeai.auth.entity.Store;
import com.storeai.auth.entity.User;
import com.storeai.auth.repository.EmployeeRepository;
import com.storeai.auth.repository.StoreRepository;
import com.storeai.auth.repository.UserRepository;
import com.storeai.auth.security.JwtUtil;
import com.storeai.auth.security.UserDetailsImpl;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工账号的受控管理入口。
 * 账号、员工身份和岗位必须在同一事务中创建；切换身份必须重新登录真实账号。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeAccountService {

    private static final Set<String> ASSIGNABLE_ROLES = Set.of(
        "manager", "consultant", "beautician", "receptionist", "operator"
    );

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
    private final CurrentUser currentUser;

    @Transactional
    public AccountView create(CreateInput input) {
        requireAdmin();
        String name = required(input.name(), "请填写姓名");
        String email = required(input.email(), "请填写登录邮箱").toLowerCase();
        String password = input.password() == null ? "" : input.password();
        String role = required(input.role(), "请选择岗位");

        if (password.length() < 6) throw BizException.badRequest("初始密码至少 6 位");
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw BizException.badRequest("只能创建店长、咨询师、美容师、前台或运营账号");
        }
        if (userRepository.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email)) != null) {
            throw BizException.badRequest("该登录邮箱已被使用");
        }

        OffsetDateTime now = OffsetDateTime.now();
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(now);
        userRepository.insert(user);

        Employee employee = new Employee();
        employee.setStoreId(currentUser.storeId());
        employee.setUserId(user.getId());
        employee.setName(name);
        employee.setPhone(blankToNull(input.phone()));
        employee.setRole(role);
        employee.setStatus("active");
        employee.setDataScope("manager".equals(role) ? "store" : "self");
        employee.setCreatedAt(now);
        employee.setUpdatedAt(now);
        employeeRepository.insert(employee);
        return view(employee, user);
    }

    /** 只给门店管理者返回同店可登录账号；不返回密码。 */
    public List<AccountView> listSwitchable() {
        requireAdmin();
        return employeeRepository.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, currentUser.storeId())
                .eq(Employee::getStatus, "active")
                .isNotNull(Employee::getUserId)
                .orderByAsc(Employee::getCreatedAt))
            .stream()
            .map(employee -> {
                User user = userRepository.selectById(employee.getUserId());
                return user == null ? null : view(employee, user);
            })
            .filter(view -> view != null)
            .toList();
    }

    /**
     * 老板在本机验收时，临时进入一位员工的真实数据/权限视图。
     *
     * 这不是改写角色字段：会签发该员工自己的会话令牌，因此前端导航、服务端
     * 数据范围和接口权限都会随目标员工生效。入口只对 owner 开放，也不返回
     * 目标账号的密码。
     */
    public PreviewLogin previewLogin(String employeeId) {
        if (!currentUser.isOwner()) throw BizException.forbidden("仅老板可体验员工身份");

        Employee employee = employeeRepository.selectById(employeeId);
        if (employee == null || !currentUser.storeId().equals(employee.getStoreId())) {
            throw BizException.notFound("员工");
        }
        if (!"active".equals(employee.getStatus())) {
            throw BizException.badRequest("该员工账号已停用，不能体验");
        }
        if ("owner".equals(employee.getRole())) {
            throw BizException.badRequest("当前已是老板身份，无需体验");
        }
        User user = employee.getUserId() == null ? null : userRepository.selectById(employee.getUserId());
        if (user == null) throw BizException.badRequest("该员工尚未创建登录账号");
        Store store = storeRepository.selectById(employee.getStoreId());
        String name = user.getName() == null ? employee.getName() : user.getName();
        String storeName = store == null || store.getName() == null ? "" : store.getName();

        UserDetailsImpl details = UserDetailsImpl.builder()
            .userId(user.getId())
            .email(user.getEmail())
            .password(user.getPasswordHash())
            .storeId(employee.getStoreId())
            .employeeId(employee.getId())
            .role(employee.getRole())
            .roleLabel(ROLE_LABELS.getOrDefault(employee.getRole(), employee.getRole()))
            .build();
        String token = jwtUtil.generateToken(details, Map.of(
            "storeId", employee.getStoreId(),
            "employeeId", employee.getId(),
            "role", employee.getRole(),
            "name", name == null ? "" : name,
            "storeName", storeName,
            "preview", true,
            "previewedBy", currentUser.userId()
        ));
        log.info("老板身份体验：sourceUserId={}, targetEmployeeId={}, targetRole={}",
            currentUser.userId(), employee.getId(), employee.getRole());
        return new PreviewLogin(
            token, user.getId(), employee.getId(), employee.getStoreId(), employee.getRole(),
            ROLE_LABELS.getOrDefault(employee.getRole(), employee.getRole()), storeName,
            name == null ? "" : name, true
        );
    }

    private void requireAdmin() {
        if (!currentUser.isAdmin()) throw BizException.forbidden("仅老板、店长或管理员可管理员工账号");
    }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw BizException.badRequest(message);
        return normalized;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static AccountView view(Employee employee, User user) {
        String role = employee.getRole();
        return new AccountView(
            employee.getId(), employee.getName(), user.getEmail(), role,
            ROLE_LABELS.getOrDefault(role, role),
            isManagementRole(role) ? "/admin" : "/work"
        );
    }

    private static boolean isManagementRole(String role) {
        return "owner".equals(role) || "manager".equals(role) || "admin".equals(role);
    }

    public record CreateInput(String name, String email, String password, String phone, String role) {}

    public record AccountView(String employeeId, String name, String email, String role,
                              String roleLabel, String entry) {}

    public record PreviewLogin(String token, String userId, String employeeId, String storeId,
                               String role, String roleLabel, String storeName, String name,
                               boolean preview) {}
}
