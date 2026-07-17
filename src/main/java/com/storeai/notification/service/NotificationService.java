package com.storeai.notification.service;

import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 轻量通知未读数聚合。
 * 替代 WebSocket 实时推送，前端可定时轮询。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    public Map<String, Object> unreadCount() {
        String storeId = cur.storeId();
        String employeeId = cur.employeeId();

        Integer tasks = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND assigned_to = ? AND status NOT IN ('done', 'canceled')
            """, Integer.class, storeId, employeeId);

        Integer pendingQuestions = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pending_questions
            WHERE store_id = ? AND status = 'pending'
              AND (employee_id = ? OR assigned_to = ?)
            """, Integer.class, storeId, employeeId, employeeId);

        Integer memoryConfirms = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND assigned_to = ? AND type = 'memory_confirm'
              AND status NOT IN ('done', 'canceled')
            """, Integer.class, storeId, employeeId);

        Integer knowledgeReviews = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND assigned_to = ? AND type = 'knowledge_review'
              AND status NOT IN ('done', 'canceled')
            """, Integer.class, storeId, employeeId);

        return Map.of(
            "tasks", tasks == null ? 0 : tasks,
            "pending_questions", pendingQuestions == null ? 0 : pendingQuestions,
            "memory_confirms", memoryConfirms == null ? 0 : memoryConfirms,
            "knowledge_reviews", knowledgeReviews == null ? 0 : knowledgeReviews
        );
    }
}
