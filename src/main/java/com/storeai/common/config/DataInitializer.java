package com.storeai.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 仅开发演示环境才允许自动写入或覆盖演示账号、客户和任务。
     * 正常本机启动默认关闭；兼容旧数据库列的检查仍会安全执行。
     */
    @Value("${app.demo.seed-on-start:false}")
    private boolean seedDemoDataOnStart;

    @Override
    public void run(String... args) {
        // 补所有缺失列（老 Supabase 表结构不一致）
        String[][] cols = {
            {"users", "auth_user_id", "VARCHAR(64)"},
            {"users", "password_hash", "VARCHAR(255)"},
            {"users", "phone", "VARCHAR(30)"},
            {"users", "wx_openid", "VARCHAR(64)"},
            {"employees", "data_scope", "VARCHAR(20) DEFAULT 'self'"},
            {"employees", "phone", "VARCHAR(30)"},
            {"knowledge_chunks", "seq", "INT DEFAULT 0"},
            {"chat_sessions", "customer_id", "VARCHAR(64)"},
            {"chat_sessions", "updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP"},
            {"chat_messages", "answer_type", "VARCHAR(30)"},
            {"chat_messages", "risk_level", "VARCHAR(10)"},
            {"chat_messages", "customer_id", "VARCHAR(64)"},
            {"chat_messages", "retrieved_chunks", "JSON"},
            {"chat_messages", "methodology_sources", "JSON"},
            {"chat_messages", "generation_mode", "VARCHAR(24)"},
            {"chat_messages", "client_request_id", "VARCHAR(80)"},
            {"chat_messages", "content", "TEXT"},
            {"chat_messages", "user_message", "TEXT"},
            {"chat_messages", "ai_response", "TEXT"},
            {"chat_messages", "question_category", "VARCHAR(100)"},
            {"chat_messages", "needs_review", "TINYINT(1) DEFAULT 0"},
            {"customers", "pool", "VARCHAR(50)"},
            {"customers", "stage", "VARCHAR(50)"},
            {"customers", "portrait", "JSON"},
            {"customers", "updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP"},
            {"customers", "source", "VARCHAR(50)"},
            {"customers", "notes", "TEXT"},
            {"customers", "import_owner_name", "VARCHAR(100)"},
            {"customers", "owner_match_status", "VARCHAR(32)"},
            {"customers", "concerns", "TEXT"},
            {"customers", "ai_suggestion", "TEXT"},
            {"customers", "ai_suggested_at", "DATETIME"},
            {"customers", "import_raw", "JSON"},
            {"customers", "last_active_at", "DATETIME"},
            {"customers", "last_deal_at", "DATETIME"},
            {"customers", "birthday", "DATE"},
            {"meetings", "employee_name", "VARCHAR(100)"},
            {"meetings", "customer_name", "VARCHAR(200)"},
            {"meetings", "duration", "INT"},
            {"meetings", "ended_at", "DATETIME"},
            {"meetings", "audio_url", "TEXT"},
            {"meetings", "audio_upload_state", "VARCHAR(24) DEFAULT 'pending'"},
            {"meetings", "audio_bytes", "BIGINT"},
            {"meetings", "audio_mime_type", "VARCHAR(120)"},
            {"meetings", "audio_received_at", "DATETIME"},
            {"meetings", "asr_task_id", "VARCHAR(128)"},
            {"meetings", "transcript_status", "VARCHAR(20) DEFAULT 'pending'"},
            {"meetings", "fail_reason", "VARCHAR(255)"},
            {"meetings", "analysis_status", "VARCHAR(20) DEFAULT 'pending'"},
            {"meetings", "analysis_attempts", "INT DEFAULT 0"},
            {"meetings", "analysis_retry_at", "DATETIME"},
            {"meetings", "analysis_error_code", "VARCHAR(64)"},
            // 未完成评估时必须为 NULL；默认 60 会污染看板平均分并误导为“已评估”。
            {"meetings", "quality_score", "INT"},
            {"meetings", "audio_duration", "INT"},
            {"meetings", "asr_submit_attempts", "INT DEFAULT 0"},
            {"meetings", "asr_submit_started_at", "DATETIME"},
            {"meetings", "asr_poll_failures", "INT DEFAULT 0"},
            {"meetings", "asr_last_polled_at", "DATETIME"},
            {"meetings", "asr_retry_at", "DATETIME"},
            {"meetings", "asr_error_code", "VARCHAR(64)"},
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
            // 早期数据库只有 report JSON，V5 又可能因历史字段已存在而未完整执行。
            // 当前会谈分析会同时读取/写入这两个字段；缺少任一字段都会在“转写已完成”
            // 后被误标为“处理失败”。这里按列幂等补齐，保留已有的阿里云历史数据。
            {"meeting_analysis", "suggested_followup_at", "DATETIME"},
            {"meeting_analysis", "suggested_script", "TEXT"},
            {"meeting_analysis", "report", "JSON"},
            {"meeting_analysis", "need_manager_involved", "TINYINT(1) DEFAULT 0"},
            {"meeting_analysis", "need_digging_score", "INT"},
            {"meeting_analysis", "deal_advancing_score", "INT"},
            {"meeting_analysis", "compliance_score", "INT"},
            {"meeting_analysis", "service_score", "INT"},
            {"meeting_analysis", "quality_score", "INT"},
            {"meeting_analysis", "quality_review_status", "VARCHAR(20)"},
            {"meeting_analysis", "quality_review_score", "INT"},
            {"meeting_analysis", "quality_review_note", "TEXT"},
            {"meeting_analysis", "quality_reviewed_by", "VARCHAR(64)"},
            {"meeting_analysis", "quality_reviewed_at", "DATETIME"},
            {"meeting_analysis", "quality_review_reason_codes", "JSON"},
            {"meeting_analysis", "compliance_hits", "TEXT"},
            {"meeting_analysis", "distilled", "TINYINT(1) DEFAULT 0"},
            {"meeting_analysis", "updated_at", "DATETIME"},
            {"meeting_consents", "store_id", "VARCHAR(64)"},
            {"tasks", "updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP"},
            {"tasks", "feedback", "TEXT"},
            {"tasks", "business_outcome_status", "VARCHAR(32) DEFAULT 'pending'"},
            {"tasks", "result_code", "VARCHAR(50)"},
            {"tasks", "result_detail", "TEXT"},
            {"tasks", "result_recorded_at", "DATETIME"},
            {"tasks", "result_verified_at", "DATETIME"},
            {"tasks", "result_verified_by", "VARCHAR(64)"},
            {"tasks", "next_follow_at", "DATETIME"},
            {"tasks", "requires_result_verification", "TINYINT(1) DEFAULT 0"},
            {"knowledge_documents", "review_status", "VARCHAR(24) DEFAULT 'approved'"},
            {"knowledge_documents", "effective_at", "DATETIME"},
            {"knowledge_documents", "expires_at", "DATETIME"},
            {"knowledge_documents", "review_due_at", "DATETIME"},
            {"knowledge_documents", "last_reviewed_at", "DATETIME"},
            {"knowledge_documents", "last_reviewed_by", "VARCHAR(64)"},
            {"knowledge_documents", "version_label", "VARCHAR(64)"},
            {"knowledge_documents", "review_note", "TEXT"},
            {"pending_questions", "category", "VARCHAR(50)"},
            {"pending_questions", "risk_level", "VARCHAR(10)"},
            {"customers", "next_follow_at", "DATETIME"},
            {"opportunities", "source", "VARCHAR(50)"},
            {"opportunities", "description", "TEXT"},
            {"knowledge_gaps", "answer", "TEXT"},
            {"knowledge_gaps", "resolved_by", "VARCHAR(64)"},
            {"knowledge_gaps", "resolved_at", "DATETIME"},
        };
        for (String[] c : cols) addColumnIfMissing(c[0], c[1], c[2]);
        // 项目表（规范多项目）：store-ai 自建 projects，stores.project_id 关联它。
        // 默认项目为「知颜」（美业门店 AI 经营助手）。始终执行，保证架构兼容。
        createProjectsTableIfMissing();
        addColumnIfMissing("stores", "project_id", "VARCHAR(64)");
        seedDefaultProject();
        safeExec("ALTER TABLE chat_messages MODIFY COLUMN user_message TEXT NULL");
        safeExec("ALTER TABLE chat_messages MODIFY COLUMN ai_response TEXT NULL");
        // 放开 employees.role CHECK 约束，允许 admin / super_admin 等角色
        safeExec("ALTER TABLE employees DROP CHECK employees_chk_1");
        safeExec("ALTER TABLE employees DROP CHECK employees_chk_2");
        // 手机号作为登录唯一标识，补唯一索引（已有平台超管账号不受影响）
        safeExec("ALTER TABLE users ADD UNIQUE KEY uk_users_phone (phone)");
        // 邮箱登录已废弃，手机号是唯一登录标识；email 改为可空
        safeExec("ALTER TABLE users MODIFY email VARCHAR(255) NULL");

        if (!seedDemoDataOnStart) {
            log.info("演示数据初始化已关闭：仅完成数据库兼容检查，不写入或覆盖演示数据");
            return;
        }

        // 历史兼容迁移：把旧的 init 占位门店（及其冗余演示数据副本）迁移到 platform
        // 占位门店，并把超管员工档案绑定到 platform。幂等，可重复执行。
        migrateInitStoreToPlatform();

        // 平台超级管理员（手机号+密码，用于创建/管理门店）。仅在 dev 演示环境自动初始化。
        seedSuperAdmin();

        // 初始化演示门店和全员角色账号（手机号+密码，不再使用邮箱登录）
        String hash = passwordEncoder.encode("demo123456");
        seedDemoData(hash);

        // 兜底：将任何未绑定项目的门店统一归属到默认项目「知颜」（幂等）
        bindStoresToDefaultProject();
    }

    // ============================================================
    // 平台超级管理员（手机号+密码，无邮箱登录）
    // ============================================================
    private void seedSuperAdmin() {
        // 平台超级管理员在后端以专属角色 super_admin 标识，可跨门店管理
        // （PC 管理后台的全局视野 + 门店切换、/api/super-admin/* 端点均依赖此角色）。
        // 仍绑定一个 platform 占位门店作为默认登录上下文，避免无门店档案导致小程序端无法登录。
        String platformStoreId = findPlatformStoreId();
        String initRole = "super_admin";

        // 两个指定的平台超级管理员（手机号+密码）
        String[][] supers = {
            {"18672348530", "wk20260801"},
            {"18627170706", "zz20260801"},
        };
        for (String[] s : supers) {
            final String phone = s[0];
            final String rawPwd = s[1];
            Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE phone = ?", Integer.class, phone);
            if (exists != null && exists > 0) {
                // 用户已存在：确认其已挂 employee（无 employee 档案时无法生成登录态），缺失则补建
                String userId = jdbc.queryForObject(
                    "SELECT id FROM users WHERE phone = ? LIMIT 1", String.class, phone);
                Integer empCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM employees WHERE user_id = ?", Integer.class, userId);
                if (empCount != null && empCount == 0) {
                    jdbc.update("INSERT INTO employees (id, store_id, user_id, name, role, status, data_scope, created_at, updated_at) VALUES (?, ?, ?, '超级管理员', ?, 'active', 'store', NOW(), NOW())",
                        uuid(), platformStoreId, userId, initRole);
                    log.info("平台超级管理员已存在但缺员工档案，已补建(门店员工,绑定platform占位门店): phone={}, role={}", phone, initRole);
                } else {
                    // 已存在员工档案：绑定到 platform 占位门店，并统一为超级管理员角色
                    jdbc.update("UPDATE employees SET store_id = ?, role = ?, updated_at = NOW() WHERE user_id = ?",
                        platformStoreId, initRole, userId);
                    log.info("平台超级管理员已存在，员工档案已绑定到platform占位门店并设为超级管理员角色: phone={}, store={}, role={}",
                        phone, platformStoreId, initRole);
                }
                continue;
            }
            String userId = uuid();
            jdbc.update("INSERT INTO users (id, email, phone, name, password_hash, created_at) VALUES (?, NULL, ?, '超级管理员', ?, NOW())",
                userId, phone, passwordEncoder.encode(rawPwd));
            jdbc.update("INSERT INTO employees (id, store_id, user_id, name, role, status, data_scope, created_at, updated_at) VALUES (?, ?, ?, '超级管理员', ?, 'active', 'store', NOW(), NOW())",
                uuid(), platformStoreId, userId, initRole);
            log.info("平台超级管理员已初始化(门店员工,绑定platform占位门店): phone={}, 密码={}, role={}", phone, rawPwd, initRole);
        }
    }

    /**
     * 查找"platform 占位门店"（超级管理员专属）：超管以专属角色 super_admin 跨门店管理，
     * 登录时仍需一个默认门店上下文（避免无门店档案导致小程序端无法登录），该占位门店不挂载
     * 真实经营数据。历史上有个同性质的 init 占位门店，已由 migrateInitStoreToPlatform() 迁移。
     */
    private String findPlatformStoreId() {
        try {
            String id = jdbc.queryForObject(
                "SELECT id FROM stores WHERE id = 'platform'", String.class);
            if (id != null && !id.isBlank()) return id;
        } catch (Exception ignored) {
            // 门店表无此门店，走创建
        }
        jdbc.update("INSERT INTO stores (id, name, owner_id, project_id, created_at, updated_at) VALUES ('platform', '平台管理', NULL, 'zhiyan', NOW(), NOW())");
        log.info("创建 platform 占位门店(供超管绑定): id=platform, name=平台管理");
        return "platform";
    }

    /**
     * 历史兼容迁移：把旧的 init 占位门店整体迁到 platform 占位门店。
     * - 超管员工档案(store_id='init')改绑到 platform；
     * - init 门店里残留的演示数据副本(客户/会谈)是尚美演示店的重复数据，清掉避免污染 platform；
     * - 删除已清空的 init 门店。
     * 幂等：init 不存在时直接跳过。无外键约束，删除安全。
     */
    private void migrateInitStoreToPlatform() {
        Integer hasInit = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE id = 'init'", Integer.class);
        if (hasInit == null || hasInit == 0) {
            log.info("migrateInitStoreToPlatform: 无 init 门店，跳过");
            return;
        }
        // 1) 超管员工档案改绑 platform
        int moved = jdbc.update("UPDATE employees SET store_id = 'platform', updated_at = NOW() WHERE store_id = 'init'");
        log.info("migrateInitStoreToPlatform: 员工档案改绑 platform 数量={}", moved);
        // 2) 清理 init 门店里的冗余演示数据副本（和尚美演示店完全重复，无外键，直接删）
        int delCustomers = jdbc.update("DELETE FROM customers WHERE store_id = 'init'");
        int delMeetings = jdbc.update("DELETE FROM meetings WHERE store_id = 'init'");
        log.info("migrateInitStoreToPlatform: 清理 init 冗余客户={}, 会谈={}", delCustomers, delMeetings);
        // 3) 删除已清空的 init 门店
        jdbc.update("DELETE FROM stores WHERE id = 'init'");
        log.info("migrateInitStoreToPlatform: 已删除 init 门店");
    }

    // ============================================================
    // 演示数据
    // ============================================================
    // 门店：尚美美容旗舰店
    // 账号（均为手机号+密码，密码 demo123456，无邮箱登录）：
    //   13700000001 王总    - owner    (老板)
    //   13700000002 张经理  - admin    (管理员)
    //   13700000003 李店长  - manager  (店长)
    //   13700000004 赵美容师- operator (店员)
    // ============================================================

    record DemoUser(String phone, String name, String role, String roleLabel) {}
    record DemoStore(String id, String name) {}

    private void seedDemoData(String passwordHash) {
        // --- 1. 查找或创建演示门店 ---
        String storeId = findOrCreateStore();

        // --- 2. 演示员工账号（手机号+密码，无邮箱登录）---
        DemoUser[] demos = {
            new DemoUser("13700000001", "王总",    "owner",    "老板"),
            new DemoUser("13700000002", "张经理",  "admin",    "管理员"),
            new DemoUser("13700000003", "李店长",  "manager",  "店长"),
            new DemoUser("13700000004", "赵美容师","operator", "店员"),
        };

        for (DemoUser d : demos) {
            String userId = findOrCreateUserByPhone(d.phone, d.name, passwordHash);
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

    /** 查找"尚美美容旗舰店"演示门店（排除 init/platform 等系统门店），不存在则创建 */
    private String findOrCreateStore() {
        try {
            String id = jdbc.queryForObject(
                "SELECT id FROM stores WHERE id <> 'init' AND id <> 'platform' ORDER BY created_at ASC LIMIT 1", String.class);
            // 统一更新门店名称
            jdbc.update("UPDATE stores SET name = ? WHERE id = ?", "尚美美容旗舰店", id);
            return id;
        } catch (Exception e) {
            // 门店表为空，创建演示门店
        }
        String id = uuid();
        jdbc.update("INSERT INTO stores (id, name, owner_id, project_id, created_at, updated_at) VALUES (?, ?, NULL, 'zhiyan', NOW(), NOW())",
            id, "尚美美容旗舰店");
        log.info("创建演示门店: id={}, name=尚美美容旗舰店", id);
        return id;
    }

    /** 按手机号查找用户，不存在则创建（邮箱留空，手机号作为唯一登录标识） */
    private String findOrCreateUserByPhone(String phone, String name, String passwordHash) {
        try {
            String id = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", String.class, phone);
            // 更新名称和密码（保证演示账号始终可用）
            jdbc.update("UPDATE users SET name = ?, password_hash = ?, email = NULL WHERE id = ?", name, passwordHash, id);
            return id;
        } catch (Exception e) {
            // 不存在，创建
        }
        String id = uuid();
        jdbc.update("INSERT INTO users (id, email, phone, name, password_hash, created_at) VALUES (?, NULL, ?, ?, ?, NOW())",
            id, phone, name, passwordHash);
        log.info("创建演示用户: phone={}, name={}", phone, name);
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

    /** 项目表（规范多项目）：store-ai 自建 projects，stores.project_id 关联它 */
    private void createProjectsTableIfMissing() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS projects (" +
                "id VARCHAR(64) PRIMARY KEY, " +
                "code VARCHAR(64) NOT NULL UNIQUE, " +
                "name VARCHAR(128) NOT NULL, " +
                "description VARCHAR(255), " +
                "status VARCHAR(20) DEFAULT 'active', " +
                "created_at DATETIME, " +
                "updated_at DATETIME)");
    }

    /** 默认项目「知颜」（美业门店 AI 经营助手）。幂等，已存在则跳过 */
    private void seedDefaultProject() {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE code = 'zhiyan'", Integer.class);
        if (cnt != null && cnt > 0) return;
        jdbc.update("INSERT INTO projects (id, code, name, description, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'active', NOW(), NOW())",
                "zhiyan", "zhiyan", "知颜", "美业门店 AI 经营助手");
        log.info("创建默认项目(知颜): code=zhiyan, name=知颜");
    }

    /** 兜底：将任何未绑定项目的门店统一归属到默认项目「知颜」。幂等 */
    private void bindStoresToDefaultProject() {
        int n = jdbc.update("UPDATE stores SET project_id = 'zhiyan' WHERE project_id IS NULL OR project_id = ''");
        if (n > 0) log.info("已将 {} 个门店归属到默认项目(知颜)", n);
    }

    private void safeExec(String sql) {
        try { jdbc.execute(sql); } catch (Exception e) { log.debug("SQL忽略: {}", e.getMessage()); }
    }
}
