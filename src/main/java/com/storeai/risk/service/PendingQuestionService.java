package com.storeai.risk.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 高风险/异常问题的确认接收闭环。
 * 状态流转：pending → assigned → handling → resolved
 */
@Service
@RequiredArgsConstructor
public class PendingQuestionService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    public List<Map<String, Object>> list() {
        String sql;
        if (cur.isAdmin()) {
            sql = "SELECT * FROM pending_questions WHERE store_id = ? ORDER BY created_at DESC";
            return jdbc.queryForList(sql, cur.storeId());
        } else {
            sql = "SELECT * FROM pending_questions WHERE store_id = ? AND (employee_id = ? OR assigned_to = ?) ORDER BY created_at DESC";
            return jdbc.queryForList(sql, cur.storeId(), cur.employeeId(), cur.employeeId());
        }
    }

    public Map<String, Object> assign(String id, String assigneeId) {
        validateStore(id);
        jdbc.update(
            "UPDATE pending_questions SET assigned_to = ?, status = 'assigned', updated_at = ? WHERE id = ?",
            assigneeId, OffsetDateTime.now().toString(), id);
        return getById(id);
    }

    public Map<String, Object> ack(String id) {
        validateStore(id);
        jdbc.update(
            "UPDATE pending_questions SET status = 'handling', updated_at = ? WHERE id = ?",
            OffsetDateTime.now().toString(), id);
        return getById(id);
    }

    public Map<String, Object> resolve(String id, String reply) {
        validateStore(id);
        jdbc.update(
            "UPDATE pending_questions SET status = 'resolved', reply = ?, updated_at = ? WHERE id = ?",
            reply, OffsetDateTime.now().toString(), id);
        return getById(id);
    }

    public Map<String, Object> escalate(String id, String reason) {
        validateStore(id);
        String origin = getQuestion(id);
        jdbc.update(
            "UPDATE pending_questions SET status = 'escalated', reply = ?, updated_at = ? WHERE id = ?",
            reason, OffsetDateTime.now().toString(), id);

        // 继续升级：创建一条新的 pending_question，risk_level 提高一级
        String newLevel = escalateLevel(getRiskLevel(id));
        jdbc.update(
            "INSERT INTO pending_questions (id, store_id, employee_id, question, ai_suggestion, category, risk_level, status, assigned_to, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', NULL, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), cur.storeId(), cur.employeeId(),
            "【升级】" + origin, reason, "会谈风险", newLevel,
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());

        return getById(id);
    }

    private Map<String, Object> getById(String id) {
        return jdbc.queryForMap("SELECT * FROM pending_questions WHERE id = ?", id);
    }

    private String getQuestion(String id) {
        try {
            return jdbc.queryForObject("SELECT question FROM pending_questions WHERE id = ?", String.class, id);
        } catch (Exception e) {
            return "";
        }
    }

    private String getRiskLevel(String id) {
        try {
            return jdbc.queryForObject("SELECT risk_level FROM pending_questions WHERE id = ?", String.class, id);
        } catch (Exception e) {
            return "L2";
        }
    }

    private String escalateLevel(String level) {
        return switch (level) {
            case "L1" -> "L2";
            case "L2" -> "L3";
            case "L3" -> "L4";
            default -> "L4";
        };
    }

    private void validateStore(String id) {
        Integer cnt = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pending_questions WHERE id = ? AND store_id = ?",
            Integer.class, id, cur.storeId());
        if (cnt == null || cnt == 0) {
            throw BizException.notFound("待处理问题");
        }
    }
}
