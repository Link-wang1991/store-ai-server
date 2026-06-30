package com.storeai.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

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
            {"chat_sessions", "updated_at", "TIMESTAMPTZ DEFAULT NOW()"},
            {"chat_messages", "answer_type", "VARCHAR(30)"},
            {"chat_messages", "risk_level", "VARCHAR(10)"},
            {"chat_messages", "customer_id", "VARCHAR(64)"},
            {"chat_messages", "retrieved_chunks", "JSONB"},
            {"chat_messages", "content", "TEXT"},
            {"chat_messages", "user_message", "TEXT"},
            {"chat_messages", "ai_response", "TEXT"},
            {"customers", "pool", "VARCHAR(50)"},
            {"customers", "stage", "VARCHAR(50)"},
            {"customers", "portrait", "JSONB"},
            {"customers", "updated_at", "TIMESTAMPTZ DEFAULT NOW()"},
            {"meetings", "updated_at", "TIMESTAMPTZ DEFAULT NOW()"},
            {"tasks", "updated_at", "TIMESTAMPTZ DEFAULT NOW()"},
            {"tasks", "feedback", "TEXT"},
        };
        for (String[] c : cols) addColumnIfMissing(c[0], c[1], c[2]);
        // user_message/ai_response 原有 NOT NULL，放开以兼容新 content 列
        safeExec("ALTER TABLE chat_messages ALTER COLUMN user_message DROP NOT NULL");
        safeExec("ALTER TABLE chat_messages ALTER COLUMN ai_response DROP NOT NULL");

        String hash = passwordEncoder.encode("demo123456");
        int updated = jdbc.update("UPDATE users SET password_hash = ? WHERE email LIKE '%@demo.com' OR email LIKE '%@store.ai'", hash);
        log.info("初始化完成: 更新{}个账号密码", updated);
    }

    private void addColumnIfMissing(String table, String column, String type) {
        try {
            jdbc.execute(String.format("ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s %s", table, column, type));
        } catch (Exception e) {
            log.debug("添加列 {}.{} 失败(可能已存在): {}", table, column, e.getMessage());
        }
    }

    private void safeExec(String sql) {
        try { jdbc.execute(sql); } catch (Exception e) { log.debug("SQL忽略: {}", e.getMessage()); }
    }
}
