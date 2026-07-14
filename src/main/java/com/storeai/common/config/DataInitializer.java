package com.storeai.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // 补所有缺失列（老 Supabase 表结构不一致）
        String[][] cols = {
            {"users", "password_hash", "VARCHAR(255)"},
            {"employees", "data_scope", "VARCHAR(20) DEFAULT 'self'"},
            {"employees", "phone", "VARCHAR(30)"},
            {"knowledge_chunks", "seq", "INT DEFAULT 0"},
            {"chat_sessions", "customer_id", "VARCHAR(64)"},
            {"chat_sessions", "updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP"},
            {"chat_messages", "answer_type", "VARCHAR(30)"},
            {"chat_messages", "risk_level", "VARCHAR(10)"},
            {"chat_messages", "customer_id", "VARCHAR(64)"},
            {"chat_messages", "retrieved_chunks", "JSON"},
            {"chat_messages", "content", "TEXT"},
            {"chat_messages", "user_message", "TEXT"},
            {"chat_messages", "ai_response", "TEXT"},
            {"chat_messages", "question_category", "VARCHAR(100)"},
            {"chat_messages", "needs_review", "TINYINT(1) DEFAULT 0"},
            {"customers", "pool", "VARCHAR(50)"},
            {"customers", "stage", "VARCHAR(50)"},
            {"customers", "portrait", "JSON"},
            {"customers", "updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP"},
            {"customers", "concerns", "TEXT"},
            {"customers", "ai_suggestion", "TEXT"},
            {"customers", "import_raw", "JSON"},
            {"customers", "last_active_at", "DATETIME"},
            {"meetings", "employee_name", "VARCHAR(100)"},
            {"meetings", "customer_name", "VARCHAR(200)"},
            {"meetings", "duration", "INT"},
            {"meetings", "ended_at", "DATETIME"},
            {"meetings", "audio_url", "TEXT"},
            {"meetings", "asr_task_id", "VARCHAR(128)"},
            {"meetings", "transcript_status", "VARCHAR(20) DEFAULT 'pending'"},
            {"meetings", "fail_reason", "VARCHAR(255)"},
            {"meetings", "analysis_status", "VARCHAR(20) DEFAULT 'pending'"},
            {"meetings", "quality_score", "INT DEFAULT 60"},
            {"meetings", "audio_duration", "INT"},
            {"meeting_transcripts", "store_id", "VARCHAR(64)"},
            {"meeting_transcripts", "speaker_role", "VARCHAR(50)"},
            {"meeting_transcripts", "start_time", "DECIMAL(10,2)"},
            {"meeting_transcripts", "end_time", "DECIMAL(10,2)"},
            {"meeting_transcripts", "updated_at", "DATETIME"},
            {"meeting_analysis", "store_id", "VARCHAR(64)"},
            {"meeting_analysis", "summary", "TEXT"},
            {"meeting_analysis", "key_points", "TEXT"},
            {"meeting_analysis", "explicit_needs", "TEXT"},
            {"meeting_analysis", "implicit_needs", "TEXT"},
            {"meeting_analysis", "emotional_needs", "TEXT"},
            {"meeting_analysis", "decision_barriers", "TEXT"},
            {"meeting_analysis", "employee_did_well", "TEXT"},
            {"meeting_analysis", "employee_to_improve", "TEXT"},
            {"meeting_analysis", "missed_opportunities", "TEXT"},
            {"meeting_analysis", "compliance_risks", "TEXT"},
            {"meeting_analysis", "followup_goal", "TEXT"},
            {"meeting_analysis", "suggested_script", "TEXT"},
            {"meeting_analysis", "need_manager_involved", "TINYINT(1) DEFAULT 0"},
            {"meeting_analysis", "need_digging_score", "INT DEFAULT 60"},
            {"meeting_analysis", "deal_advancing_score", "INT DEFAULT 60"},
            {"meeting_analysis", "compliance_score", "INT DEFAULT 60"},
            {"meeting_analysis", "service_score", "INT DEFAULT 60"},
            {"meeting_analysis", "quality_score", "INT DEFAULT 60"},
            {"meeting_analysis", "compliance_hits", "TEXT"},
            {"meeting_analysis", "distilled", "TINYINT(1) DEFAULT 0"},
            {"meeting_analysis", "updated_at", "DATETIME"},
            {"meeting_consents", "store_id", "VARCHAR(64)"},
            {"tasks", "updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP"},
            {"tasks", "feedback", "TEXT"},
        };
        for (String[] c : cols) addColumnIfMissing(c[0], c[1], c[2]);
        safeExec("ALTER TABLE chat_messages MODIFY COLUMN user_message TEXT NULL");
        safeExec("ALTER TABLE chat_messages MODIFY COLUMN ai_response TEXT NULL");
        // 放开 employees.role CHECK 约束，允许 admin 角色
        safeExec("ALTER TABLE employees DROP CHECK employees_chk_1");
        safeExec("ALTER TABLE employees DROP CHECK employees_chk_2");

        // 设置演示密码
        String hash = passwordEncoder.encode("demo123456");
        int updated = jdbc.update("UPDATE users SET password_hash = ? WHERE email LIKE '%@demo.com' OR email LIKE '%@store.ai'", hash);
        log.info("密码更新完成: 更新{}个账号", updated);

        // 初始化演示门店和全员角色账号
        seedDemoData(hash);
    }

    // ============================================================
    // 演示数据
    // ============================================================
    // 门店：尚美美容旗舰店
    // 账号（密码均为 demo123456）：
    //   owner@demo.com     王总    - owner    (老板)
    //   admin@demo.com            张经理   - admin    (管理员)
    //   manager@demo.com         李店长   - manager  (店长)
    //   staff@demo.com           赵美容师  - operator (店员)
    // ============================================================

    record DemoUser(String email, String name, String role, String roleLabel) {}
    record DemoStore(String id, String name) {}

    private void seedDemoData(String passwordHash) {
        // --- 1. 查找或创建演示门店 ---
        String storeId = findOrCreateStore();

        // --- 2. 演示员工账号 ---
        DemoUser[] demos = {
            new DemoUser("owner@demo.com",   "王总",   "owner",    "老板"),
            new DemoUser("admin@demo.com", "张经理",  "admin",    "管理员"),
            new DemoUser("manager@demo.com",  "李店长",  "manager",  "店长"),
            new DemoUser("staff@demo.com",    "赵美容师","operator", "店员"),
        };

        for (DemoUser d : demos) {
            String userId = findOrCreateUser(d.email, d.name, passwordHash);
            findOrCreateEmployee(storeId, userId, d.name, d.role);
        }

        log.info("演示数据初始化完成: 门店={}, 员工={}人", storeId, demos.length);

        // --- 3. 默认咨询场景 ---
        seedScenes(storeId);

        // --- 4. 演示客户 + 任务 ---
        seedDemoCustomersAndTasks(storeId, passwordHash);
    }

    private void seedScenes(String storeId) {
        // 只插入一次，已有数据不覆盖
        int existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM store_config WHERE store_id = ? AND category = 'meeting_scene'", Integer.class, storeId);
        if (existing > 0) return;

        String[][] scenes = {
            {"new_consult", "新客咨询", "1"},
            {"project_intro", "项目介绍", "2"},
            {"deal_consult", "成交沟通", "3"},
            {"pre_service", "服务前沟通", "4"},
            {"post_service", "服务后反馈", "5"},
            {"repurchase", "老客复购", "6"},
            {"complaint", "客户投诉", "7"},
            {"campaign_invite", "活动邀约", "8"},
            {"price_objection", "价格异议", "9"},
            {"effect_doubt", "效果疑虑", "10"},
        };
        for (String[] s : scenes) {
            jdbc.update(
                "INSERT INTO store_config (id, store_id, category, code, display_name, enabled, sort_order, created_at) VALUES (?, ?, 'meeting_scene', ?, ?, TRUE, ?, NOW())",
                UUID.randomUUID().toString().replace("-", ""), storeId, s[0], s[1], Integer.parseInt(s[2]));
        }
        log.info("默认咨询场景已初始化: {}条", scenes.length);
    }

    private void seedDemoCustomersAndTasks(String storeId, String passwordHash) {
        // 找老板和店长的 employee_id
        String ownerEmpId = findEmpIdByRole(storeId, "owner");
        String managerEmpId = findEmpIdByRole(storeId, "manager");
        String operatorEmpId = findEmpIdByRole(storeId, "operator");
        if (ownerEmpId == null) return;

        // --- 客户（清掉旧数据重建，确保分配正确）---
        jdbc.update("DELETE FROM customers WHERE store_id = ?", storeId);
        String[][] custs = {
                {"李姐", "13800138001", "female", "35", "intent", "new_deal"},
                {"张姐", "13800138002", "female", "42", "regular", "regular"},
                {"王女士", "13800138003", "female", "28", "new", "new_deal"},
                {"刘阿姨", "13800138004", "female", "55", "deal", "today"},
                {"陈先生", "13800138005", "male",   "32", "new", "new"},
            };
            for (String[] c : custs) {
                String empId = Math.random() > 0.5 ? ownerEmpId : (managerEmpId != null ? managerEmpId : ownerEmpId);
                jdbc.update("INSERT INTO customers (id, store_id, name, phone, gender, age, stage, pool, assigned_to, total_visits, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(), NOW())",
                    uuid(), storeId, c[0], c[1], c[2], Integer.parseInt(c[3]), c[4], c[5], empId);
            }
            log.info("创建演示客户: {} 人", custs.length);

        // --- 任务 ---
        if (countRows("tasks") == 0) {
            Object[][] tasks = {
                {"跟进李姐的祛斑项目意向", "李姐上次咨询了祛斑项目，请跟进确认意向", "followup", ownerEmpId, ownerEmpId},
                {"回访张姐的服务体验", "张姐做完光子嫩肤一周，回访效果", "followup", ownerEmpId, managerEmpId != null ? managerEmpId : ownerEmpId},
                {"准备刘阿姨的签约方案", "刘阿姨对年卡有明确意向，出方案报价", "deal", ownerEmpId, ownerEmpId},
                {"王女士生日关怀", "王女士本周生日，发送祝福和优惠券", "reminder", ownerEmpId, operatorEmpId != null ? operatorEmpId : ownerEmpId},
            };
            for (Object[] t : tasks) {
                jdbc.update("INSERT INTO tasks (id, store_id, title, content, type, status, assigned_to, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'todo', ?, ?, NOW(), NOW())",
                    uuid(), storeId, t[0], t[1], t[2], t[3], t[4]);
            }
            log.info("创建演示任务: {} 条", tasks.length);
        }
    }

    private String findEmpIdByRole(String storeId, String role) {
        try {
            return jdbc.queryForObject("SELECT id FROM employees WHERE store_id = ? AND role = ? LIMIT 1", String.class, storeId, role);
        } catch (Exception e) {
            return null;
        }
    }

    private int countRows(String table) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 查找第一个门店，不存在则创建 */
    private String findOrCreateStore() {
        try {
            String id = jdbc.queryForObject("SELECT id FROM stores ORDER BY created_at ASC LIMIT 1", String.class);
            // 统一更新门店名称
            jdbc.update("UPDATE stores SET name = ? WHERE id = ?", "尚美美容旗舰店", id);
            return id;
        } catch (Exception e) {
            // 门店表为空，创建演示门店
        }
        String id = uuid();
        jdbc.update("INSERT INTO stores (id, name, owner_id, created_at, updated_at) VALUES (?, ?, NULL, NOW(), NOW())",
            id, "尚美美容旗舰店");
        log.info("创建演示门店: id={}, name=尚美美容旗舰店", id);
        return id;
    }

    /** 按邮箱查找用户，不存在则创建 */
    private String findOrCreateUser(String email, String name, String passwordHash) {
        try {
            String id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
            // 更新名称和密码（保证演示账号始终可用）
            jdbc.update("UPDATE users SET name = ?, password_hash = ? WHERE id = ?", name, passwordHash, id);
            return id;
        } catch (Exception e) {
            // 不存在，创建
        }
        String id = uuid();
        jdbc.update("INSERT INTO users (id, email, name, password_hash, created_at) VALUES (?, ?, ?, ?, NOW())",
            id, email, name, passwordHash);
        log.info("创建演示用户: email={}, name={}", email, name);
        return id;
    }

    /** 按 user_id 查找员工记录，不存在则创建 */
    private void findOrCreateEmployee(String storeId, String userId, String name, String role) {
        try {
            String empId = jdbc.queryForObject("SELECT id FROM employees WHERE user_id = ?", String.class, userId);
            // 更新信息
            jdbc.update("UPDATE employees SET store_id = ?, name = ?, role = ?, status = 'active', data_scope = ?, updated_at = NOW() WHERE id = ?",
                storeId, name, role, "owner".equals(role) ? "store" : "self", empId);
            return;
        } catch (Exception e) {
            // 不存在，创建
        }
        String id = uuid();
        String dataScope = "owner".equals(role) ? "store" : "self";
        jdbc.update("INSERT INTO employees (id, store_id, user_id, name, role, status, data_scope, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'active', ?, NOW(), NOW())",
            id, storeId, userId, name, role, dataScope);
        log.info("创建演示员工: name={}, role={}, storeId={}", name, role, storeId);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void addColumnIfMissing(String table, String column, String type) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ? AND TABLE_SCHEMA = DATABASE()",
                Integer.class, table, column);
            if (count != null && count > 0) return;
            jdbc.execute(String.format("ALTER TABLE %s ADD COLUMN %s %s", table, column, type));
        } catch (Exception e) {
            log.debug("添加列 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    private void safeExec(String sql) {
        try { jdbc.execute(sql); } catch (Exception e) { log.debug("SQL忽略: {}", e.getMessage()); }
    }
}
