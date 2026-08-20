package com.storeai.admin.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 经营报告（增长复盘）：汇总近期关键经营指标并生成可追溯报告。
 */
@Service
@RequiredArgsConstructor
public class BusinessReportService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    /** 报告列表。 */
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
            SELECT id, store_id, type, content, report_date, created_at
            FROM reports WHERE store_id = ? ORDER BY created_at DESC LIMIT 100
            """, cur.storeId());
    }

    /** 生成一份新的经营报告（当前经营快照）。 */
    public Map<String, Object> generate(String type) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        String storeId = cur.storeId();
        String safeType = type == null || type.isBlank() ? "weekly" : type;

        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("generated_at", java.time.OffsetDateTime.now().toString());
        content.put("type", safeType);
        content.put("today_meetings", todayMeetings(storeId));
        content.put("week_meetings", weekMeetings(storeId));
        content.put("tasks", taskOverview(storeId));
        content.put("customers", customerCount(storeId));
        content.put("risk_open", riskOpen(storeId));
        content.put("knowledge_active", knowledgeActive(storeId));

        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
            INSERT INTO reports (id, store_id, type, content, report_date, created_at)
            VALUES (?, ?, ?, ?, ?, NOW())
            """, id, storeId, safeType, toJson(content), LocalDate.now());
        return jdbc.queryForMap("SELECT * FROM reports WHERE id = ?", id);
    }

    private Map<String, Object> todayMeetings(String storeId) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT COUNT(*) AS count, COALESCE(AVG(quality_score), 0) AS avg_score
            FROM meetings WHERE store_id = ? AND DATE(created_at) = CURDATE()
            """, storeId);
        return Map.of("count", row.get("count"), "avg_quality_score", row.get("avg_score"));
    }

    private int weekMeetings(String storeId) {
        Integer c = jdbc.queryForObject("""
            SELECT COUNT(*) FROM meetings
            WHERE store_id = ? AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            """, Integer.class, storeId);
        return c == null ? 0 : c;
    }

    private Map<String, Object> taskOverview(String storeId) {
        Integer pending = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND status NOT IN ('done', 'canceled')
            """, Integer.class, storeId);
        Integer done = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND status = 'done'
              AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            """, Integer.class, storeId);
        return Map.of("pending", pending == null ? 0 : pending, "done_7d", done == null ? 0 : done);
    }

    private int customerCount(String storeId) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM customers WHERE store_id = ?", Integer.class, storeId);
        return c == null ? 0 : c;
    }

    private int riskOpen(String storeId) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM risk_logs WHERE store_id = ? AND status = 'open'", Integer.class, storeId);
        return c == null ? 0 : c;
    }

    private int knowledgeActive(String storeId) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_documents WHERE store_id = ? AND status = 'active'", Integer.class, storeId);
        return c == null ? 0 : c;
    }

    private String toJson(Map<String, Object> content) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content);
        } catch (Exception e) {
            throw new BizException("报告内容序列化失败");
        }
    }
}
