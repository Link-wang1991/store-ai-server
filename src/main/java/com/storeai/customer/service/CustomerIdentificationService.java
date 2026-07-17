package com.storeai.customer.service;

import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 到店客户识别卡。
 * 支持按手机号、姓名等关键字识别客户，避免仅凭姓名串档。
 */
@Service
@RequiredArgsConstructor
public class CustomerIdentificationService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    public List<Map<String, Object>> identify(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String kw = keyword.trim();

        // 1. 手机号精确匹配
        List<Map<String, Object>> results = queryByPhone(kw);
        if (!results.isEmpty()) return results;

        // 2. 姓名模糊匹配（限制 10 条，避免同名混淆）
        results = queryByName(kw);

        // 3. 补充识别信息
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (var c : results) {
            enriched.add(enrich(c));
        }
        return enriched;
    }

    private List<Map<String, Object>> queryByPhone(String phone) {
        try {
            return jdbc.queryForList(
                "SELECT * FROM customers WHERE store_id = ? AND phone = ? LIMIT 5",
                cur.storeId(), phone);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> queryByName(String name) {
        try {
            return jdbc.queryForList(
                "SELECT * FROM customers WHERE store_id = ? AND name LIKE ? LIMIT 10",
                cur.storeId(), "%" + name + "%");
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> enrich(Map<String, Object> customer) {
        Map<String, Object> result = new LinkedHashMap<>(customer);
        String customerId = (String) customer.get("id");

        // 最近到店时间
        String lastCheckin = queryString(
            "SELECT MAX(created_at) FROM interactions WHERE customer_id = ? AND type = 'checkin'",
            customerId);
        result.put("last_checkin_at", lastCheckin == null ? "" : lastCheckin);

        // 今日是否已签到
        Integer todayCheckin = jdbc.queryForObject(
            "SELECT COUNT(*) FROM interactions WHERE customer_id = ? AND type = 'checkin' AND DATE(created_at) = CURDATE()",
            Integer.class, customerId);
        result.put("checked_in_today", todayCheckin != null && todayCheckin > 0);

        // 负责人姓名
        String ownerName = queryString(
            "SELECT name FROM employees WHERE id = ? LIMIT 1", customer.get("assigned_to"));
        result.put("assigned_to_name", ownerName == null ? "" : ownerName);

        // 最近事项：未完成任务
        List<Map<String, Object>> pendingTasks = jdbc.queryForList(
            "SELECT title, type, status FROM tasks WHERE store_id = ? AND assigned_to = ? AND status != 'done' AND status != 'canceled' ORDER BY created_at DESC LIMIT 3",
            cur.storeId(), customer.get("assigned_to"));
        result.put("recent_tasks", pendingTasks);

        // 最近会谈摘要
        String latestSummary = queryString(
            "SELECT ma.summary FROM meetings m LEFT JOIN meeting_analysis ma ON ma.meeting_id = m.id " +
            "WHERE m.customer_id = ? AND m.store_id = ? ORDER BY m.ended_at DESC LIMIT 1",
            customerId, cur.storeId());
        result.put("latest_meeting_summary", latestSummary == null ? "" : latestSummary);

        return result;
    }

    private String queryString(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, String.class, args);
        } catch (Exception e) {
            return null;
        }
    }
}
