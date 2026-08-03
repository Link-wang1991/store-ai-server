package com.storeai.auth.service;

import com.storeai.auth.entity.Employee;
import com.storeai.auth.entity.Store;
import com.storeai.auth.entity.User;
import com.storeai.auth.repository.EmployeeRepository;
import com.storeai.auth.repository.StoreRepository;
import com.storeai.auth.repository.UserRepository;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台超级管理员专属：跨门店的门店录入、管理与初始化。
 * 所有入口由 Controller 层 {@code isSuperAdmin()} 守卫；本 Service 不再重复校验。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminService {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    /** 门店列表 + 基础统计（跨门店）。 */
    public List<StoreSummary> listStores() {
        String sql = """
            SELECT s.id, s.name, s.created_at,
                   (SELECT COUNT(*) FROM employees e WHERE e.store_id = s.id AND e.status = 'active') AS employee_count,
                   (SELECT COUNT(*) FROM customers c WHERE c.store_id = s.id) AS customer_count,
                   (SELECT COUNT(*) FROM meetings m WHERE m.store_id = s.id) AS meeting_count
            FROM stores s
            ORDER BY s.created_at DESC
            """;
        return jdbc.queryForList(sql).stream().map(row -> new StoreSummary(
            str(row.get("id")),
            str(row.get("name")),
            num(row.get("employee_count")),
            num(row.get("customer_count")),
            num(row.get("meeting_count")),
            ts(row.get("created_at"))
        )).toList();
    }

    @Transactional
    public StoreSummary createStore(CreateStoreInput input) {
        String name = required(input.name(), "请填写门店名称");
        String ownerName = required(input.ownerName(), "请填写负责人姓名");
        String ownerPhone = required(input.ownerPhone(), "请填写负责人手机号").trim();
        String ownerPassword = input.ownerPassword() == null ? "" : input.ownerPassword();
        if (!ownerPhone.matches("^1[3-9]\\d{9}$")) throw BizException.badRequest("负责人手机号格式不正确");
        if (ownerPassword.length() < 6) throw BizException.badRequest("初始密码至少 6 位");

        if (userRepository.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>().eq(User::getPhone, ownerPhone)) != null) {
            throw BizException.badRequest("该手机号已绑定其他账号");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Store store = new Store();
        store.setName(name);
        store.setOwnerId(null);
        store.setCreatedAt(now);
        store.setUpdatedAt(now);
        storeRepository.insert(store);

        User user = new User();
        user.setEmail(ownerPhone + "@store.ai");
        user.setPhone(ownerPhone);
        user.setName(ownerName);
        user.setPasswordHash(passwordEncoder.encode(ownerPassword));
        user.setCreatedAt(now);
        userRepository.insert(user);

        Employee employee = new Employee();
        employee.setStoreId(store.getId());
        employee.setUserId(user.getId());
        employee.setName(ownerName);
        employee.setPhone(ownerPhone);
        employee.setRole("owner");
        employee.setStatus("active");
        employee.setDataScope("store");
        employee.setCreatedAt(now);
        employee.setUpdatedAt(now);
        employeeRepository.insert(employee);

        // 初始化该门店的默认咨询场景与知识库
        initStoreInternal(store.getId());

        log.info("超级管理员创建门店: storeId={}, name={}, ownerPhone={}", store.getId(), name, ownerPhone);
        return new StoreSummary(store.getId(), name, 1, 0, 0, store.getCreatedAt() == null ? null : store.getCreatedAt().toString());
    }

    /** 为指定门店初始化默认咨询场景与知识库。 */
    @Transactional
    public void initStore(String storeId) {
        if (storeRepository.selectById(storeId) == null) throw BizException.notFound("门店");
        initStoreInternal(storeId);
        log.info("超级管理员初始化门店资料: storeId={}", storeId);
    }

    private void initStoreInternal(String storeId) {
        // 默认咨询场景（仅插入一次）
        int existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM store_config WHERE store_id = ? AND category = 'meeting_scene'", Integer.class, storeId);
        if (existing == 0) {
            String[][] scenes = {
                {"new_consult", "新客咨询", "1"}, {"project_intro", "项目介绍", "2"}, {"deal_consult", "成交沟通", "3"},
                {"pre_service", "服务前沟通", "4"}, {"post_service", "服务后反馈", "5"}, {"repurchase", "老客复购", "6"},
                {"complaint", "客户投诉", "7"}, {"campaign_invite", "活动邀约", "8"}, {"price_objection", "价格异议", "9"},
                {"effect_doubt", "效果疑虑", "10"},
            };
            for (String[] s : scenes) {
                jdbc.update(
                    "INSERT INTO store_config (id, store_id, category, code, display_name, enabled, sort_order, created_at) VALUES (?, ?, 'meeting_scene', ?, ?, TRUE, ?, NOW())",
                    UUID.randomUUID().toString().replace("-", ""), storeId, s[0], s[1], Integer.parseInt(s[2]));
            }
        }

        // 默认知识库（门店经营常识一篇，便于 AI 教练引用）
        int kd = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE store_id = ?", Integer.class, storeId);
        if (kd == 0) {
            jdbc.update(
                "INSERT INTO knowledge_documents (id, store_id, title, category, status, visible_roles, created_at, updated_at) VALUES (?, ?, '门店服务与接待标准', '门店手册', 'active', NULL, NOW(), NOW())",
                UUID.randomUUID().toString().replace("-", ""), storeId);
        }
    }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw BizException.badRequest(message);
        return normalized;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static int num(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
    private static String ts(Object o) { return o == null ? null : o.toString(); }

    public record CreateStoreInput(String name, String ownerName, String ownerPhone, String ownerPassword) {}
    public record StoreSummary(String id, String name, int employeeCount, int customerCount, int meetingCount, String createdAt) {}
}
