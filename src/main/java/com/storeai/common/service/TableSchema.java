package com.storeai.common.service;

import com.storeai.common.exception.BizException;

import java.util.Set;

/**
 * 表 schema 元信息：白名单 + store_id 字段判定。
 * 新增表时在这里添加即可自动支持。
 */
public class TableSchema {

    // V1 建的所有表白名单
    private static final Set<String> VALID_TABLES = Set.of(
        "stores", "users", "roles", "role_definitions", "role_permissions",
        "employees", "knowledge_documents", "knowledge_chunks",
        "chat_sessions", "chat_messages", "pending_questions",
        "knowledge_gaps", "risk_logs", "tasks", "banned_words",
        "standard_answers", "customers", "interactions",
        "memory_items", "opportunities", "announcements", "reports",
        "store_config", "activities", "meetings", "meeting_transcripts",
        "meeting_analysis", "meeting_consents", "meeting_access_logs",
        "playbooks", "followups", "customer_feedback",
        "service_projects", "campaigns", "schedules"
    );

    // 有多门店隔离的表（查询时自动加 store_id=current）
    // 注意：stores/users 是全局的，不加 store_id 过滤
    private static final Set<String> TABLES_WITH_STORE_ID = Set.of(
        "employees",
        "knowledge_documents", "knowledge_chunks",
        "chat_sessions", "chat_messages",
        "pending_questions", "knowledge_gaps", "risk_logs",
        "tasks", "banned_words", "standard_answers",
        "customers", "interactions", "memory_items",
        "opportunities", "announcements", "reports",
        "store_config", "activities",
        "meetings", "meeting_transcripts", "meeting_analysis",
        "meeting_consents", "meeting_access_logs",
        "playbooks", "followups", "customer_feedback",
        "service_projects", "campaigns", "schedules"
    );

    // 消息记录是只追加表，只有 created_at，没有 updated_at。
    private static final Set<String> TABLES_WITHOUT_UPDATED_AT = Set.of("chat_messages");

    /** 校验并返回规范化的表名 */
    public String validateTable(String table) {
        String t = table.toLowerCase().trim();
        if (!VALID_TABLES.contains(t)) {
            throw BizException.badRequest("不支持的表: " + table);
        }
        return t;
    }

    /** 该表是否有多门店隔离 */
    public boolean hasStoreId(String table) {
        return TABLES_WITH_STORE_ID.contains(table);
    }

    public boolean hasUpdatedAt(String table) {
        return !TABLES_WITHOUT_UPDATED_AT.contains(table);
    }

    /** 列名校验（仅检查合法性，不做全量白名单） */
    public boolean isValidColumn(String table, String column) {
        if (column == null || column.isEmpty()) return false;
        // 只允许字母数字下划线（防止 SQL 注入）
        return column.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }
}
