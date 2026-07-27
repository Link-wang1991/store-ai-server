package com.storeai.home.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页只读取一份可追溯的工作数据：客户、分配给当前员工的任务，以及任务来源。
 *
 * 客户机会池的展示分类仍由前端共享规则计算；这里刻意不再额外生成另一套池结果，
 * 避免首页和客户页出现“同一客户两个分组”的口径分叉。
 */
@Service
@RequiredArgsConstructor
public class HomeOverviewService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final ObjectMapper mapper;

    public Map<String, Object> overview() {
        List<Map<String, Object>> customers = listCustomers();
        List<Map<String, Object>> tasks = listOpenTasksWithTrace();
        long pendingExperienceReviews = tasks.stream()
            .filter(task -> "experience_review".equals(string(task.get("type"))))
            .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customers", customers);
        result.put("tasks", tasks);
        // 这个数字只统计真正待审核的会谈经验，不能再混用“未分析会谈”数量。
        result.put("pending_experience_reviews", pendingExperienceReviews);
        return result;
    }

    private List<Map<String, Object>> listCustomers() {
        String sql = """
            SELECT id, name, phone, stage, pool, concerns, ai_suggestion,
                   last_visit_at, last_deal_at, last_active_at, next_follow_at,
                   import_raw, assigned_to, created_at, updated_at
            FROM customers
            WHERE store_id = ?
            """ + (cur.isAdmin() ? " ORDER BY updated_at DESC" : " AND assigned_to = ? ORDER BY updated_at DESC");
        List<Map<String, Object>> rows = cur.isAdmin()
            ? jdbc.queryForList(sql, cur.storeId())
            : jdbc.queryForList(sql, cur.storeId(), cur.employeeId());
        for (Map<String, Object> row : rows) {
            Object importRaw = row.get("import_raw");
            if (importRaw instanceof String text && !text.isBlank()) {
                try {
                    row.put("import_raw", mapper.readValue(text, Object.class));
                } catch (Exception ignored) {
                    // 导入原文格式异常时保持原值，不能影响首页工作台加载。
                }
            }
        }
        return rows;
    }

    private List<Map<String, Object>> listOpenTasksWithTrace() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT t.id, t.customer_id, t.title, t.content, t.type, t.status, t.priority,
                   t.assigned_to, t.due_at, t.feedback, t.source_type, t.source_id,
                   t.source_meeting_id, t.created_at, t.updated_at,
                   c.name AS customer_name, c.stage AS customer_stage
            FROM tasks t
            LEFT JOIN customers c ON c.id = t.customer_id AND c.store_id = t.store_id
            WHERE t.store_id = ?
              AND t.assigned_to = ?
              AND t.status IN ('todo', 'doing', 'overdue')
            ORDER BY CASE t.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 ELSE 2 END,
                     CASE WHEN t.due_at IS NULL THEN 1 ELSE 0 END,
                     t.due_at ASC, t.created_at DESC
            """, cur.storeId(), cur.employeeId());

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> task = new LinkedHashMap<>(row);
            String customerId = string(row.get("customer_id"));
            if (!customerId.isBlank()) {
                Map<String, Object> customer = new LinkedHashMap<>();
                customer.put("id", customerId);
                customer.put("name", row.get("customer_name"));
                customer.put("stage", row.get("customer_stage"));
                task.put("customer", customer);
            }
            task.put("source_label", sourceLabel(string(row.get("source_type"))));
            task.put("knowledge_evidence", knowledgeEvidence(row));
            tasks.add(task);
        }
        return tasks;
    }

    /** AI 教练任务能回溯到当时实际检索出的知识片段；其它来源不伪造“知识依据”。 */
    private List<Map<String, String>> knowledgeEvidence(Map<String, Object> task) {
        if (!"ai_coach".equals(string(task.get("source_type"))) || string(task.get("source_id")).isBlank()) {
            return List.of();
        }
        try {
            List<String> values = jdbc.queryForList("""
                SELECT cm.retrieved_chunks
                FROM ai_action_proposals ap
                JOIN chat_messages cm ON cm.id = ap.message_id AND cm.store_id = ap.store_id
                WHERE ap.id = ? AND ap.store_id = ?
                LIMIT 1
                """, String.class, string(task.get("source_id")), cur.storeId());
            if (values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) return List.of();

            JsonNode nodes = mapper.readTree(values.get(0));
            if (!nodes.isArray()) return List.of();
            List<Map<String, String>> result = new ArrayList<>();
            for (JsonNode node : nodes) {
                String documentId = text(node, "id");
                String title = text(node, "documentTitle");
                String excerpt = text(node, "content");
                if (documentId.isBlank() && title.isBlank()) continue;
                Map<String, String> item = new LinkedHashMap<>();
                item.put("document_id", documentId);
                item.put("title", title.isBlank() ? "门店知识" : title);
                item.put("excerpt", excerpt);
                result.add(item);
                if (result.size() >= 3) break;
            }
            return result;
        } catch (Exception ignored) {
            // 首页证据读取失败不能阻断正式待办；前端会诚实显示“未记录知识引用”。
            return List.of();
        }
    }

    private String sourceLabel(String sourceType) {
        return switch (sourceType) {
            case "meeting_analysis" -> "会谈分析";
            case "ai_coach" -> "AI 教练建议";
            case "task_feedback" -> "上次任务反馈";
            case "experience_review" -> "会谈经验审核";
            case "memory_confirm" -> "客户记忆确认";
            case "manual_meeting_candidate" -> "人工提交的会谈经验";
            default -> sourceType == null || sourceType.isBlank() ? "人工创建" : sourceType;
        };
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
