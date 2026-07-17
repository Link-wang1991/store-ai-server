package com.storeai.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 店长驾驶舱数据服务。
 * 汇总今日会谈、合规风险、任务逾期、员工排行、低分会谈等关键指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManagerDashboardService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> buildDashboard() {
        if (!cur.isAdmin()) {
            throw BizException.forbidden();
        }

        String storeId = cur.storeId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today_meetings", todayMeetings(storeId));
        result.put("weekly_compliance_hits", weeklyComplianceHits(storeId));
        result.put("tasks", taskOverview(storeId));
        result.put("employee_ranking", employeeRanking(storeId));
        result.put("low_score_meetings", lowScoreMeetings(storeId));
        result.put("pending_questions_count", pendingQuestionsCount(storeId));
        result.put("generated_at", OffsetDateTime.now().toString());
        return result;
    }

    private Map<String, Object> todayMeetings(String storeId) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT COUNT(*) AS total, COALESCE(AVG(quality_score), 0) AS avg_score
            FROM meetings
            WHERE store_id = ? AND DATE(created_at) = CURDATE()
            """, storeId);
        return Map.of(
            "count", row.get("total"),
            "avg_quality_score", row.get("avg_score")
        );
    }

    private Map<String, Integer> weeklyComplianceHits(String storeId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("L1", 0);
        counts.put("L2", 0);
        counts.put("L3", 0);
        counts.put("L4", 0);

        List<String> jsonList = jdbc.queryForList("""
            SELECT compliance_hits FROM meeting_analysis
            WHERE store_id = ? AND compliance_hits IS NOT NULL AND compliance_hits != ''
              AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            """, String.class, storeId);

        for (String json : jsonList) {
            try {
                List<Map<String, Object>> hits = mapper.readValue(json, new TypeReference<>() {});
                for (Map<String, Object> hit : hits) {
                    Object levelObj = hit.get("level");
                    int level = levelObj instanceof Number n ? n.intValue()
                        : Integer.parseInt(String.valueOf(levelObj));
                    String key = "L" + Math.min(Math.max(level, 1), 4);
                    counts.put(key, counts.get(key) + 1);
                }
            } catch (Exception e) {
                log.debug("合规命中 JSON 解析失败: {}", e.getMessage());
            }
        }
        return counts;
    }

    private Map<String, Object> taskOverview(String storeId) {
        Integer pending = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND status NOT IN ('done', 'canceled')
            """, Integer.class, storeId);
        Integer overdue = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND status NOT IN ('done', 'canceled')
              AND due_at IS NOT NULL AND due_at < NOW()
            """, Integer.class, storeId);
        return Map.of(
            "pending", pending == null ? 0 : pending,
            "overdue", overdue == null ? 0 : overdue
        );
    }

    private List<Map<String, Object>> employeeRanking(String storeId) {
        return jdbc.queryForList("""
            SELECT m.employee_id, e.name AS employee_name,
                   COUNT(*) AS meeting_count,
                   ROUND(AVG(m.quality_score), 1) AS avg_score
            FROM meetings m
            LEFT JOIN employees e ON e.id = m.employee_id
            WHERE m.store_id = ? AND m.quality_score IS NOT NULL
              AND m.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            GROUP BY m.employee_id
            ORDER BY avg_score DESC
            LIMIT 5
            """, storeId);
    }

    private List<Map<String, Object>> lowScoreMeetings(String storeId) {
        return jdbc.queryForList("""
            SELECT m.id, m.scene, m.quality_score, m.created_at,
                   e.name AS employee_name, c.name AS customer_name
            FROM meetings m
            LEFT JOIN employees e ON e.id = m.employee_id
            LEFT JOIN customers c ON c.id = m.customer_id
            WHERE m.store_id = ? AND m.quality_score IS NOT NULL AND m.quality_score < 50
            ORDER BY m.created_at DESC
            LIMIT 10
            """, storeId);
    }

    private int pendingQuestionsCount(String storeId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pending_questions WHERE store_id = ? AND status = 'pending'
            """, Integer.class, storeId);
        return count == null ? 0 : count;
    }
}
