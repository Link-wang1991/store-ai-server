package com.storeai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 客户池自动分类定时任务。
 * 每天凌晨 1:00 根据客户活跃度、阶段、预约/签到等情况更新 customers.pool。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerPoolClassifierJob {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 1 * * ?")
    public void run() {
        log.info("开始执行客户池自动分类");
        List<String> storeIds = jdbc.queryForList("SELECT DISTINCT store_id FROM customers WHERE store_id IS NOT NULL", String.class);

        int total = 0;
        for (String storeId : storeIds) {
            total += classifyStore(storeId);
        }
        log.info("客户池自动分类完成，共更新 {} 个客户", total);
    }

    private int classifyStore(String storeId) {
        // 优先级：today > risk > dormant > new_deal > new > regular
        String sql = """
            UPDATE customers
            SET pool = CASE
                WHEN DATE(last_visit_at) = CURDATE()
                  OR DATE(next_follow_at) = CURDATE()
                  OR EXISTS (
                      SELECT 1 FROM interactions i
                      WHERE i.customer_id = customers.id
                        AND i.type = 'checkin'
                        AND DATE(i.created_at) = CURDATE()
                  ) THEN 'today'
                WHEN stage = 'churn_risk'
                  OR EXISTS (
                      SELECT 1 FROM pending_questions pq
                      WHERE pq.store_id = customers.store_id
                        AND pq.employee_id = customers.assigned_to
                        AND pq.status = 'pending'
                        AND pq.risk_level IN ('L3', 'L4')
                        AND pq.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
                  )
                  OR EXISTS (
                      SELECT 1 FROM meetings m
                      WHERE m.store_id = customers.store_id
                        AND m.customer_id = customers.id
                        AND m.quality_score < 50
                        AND m.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
                  ) THEN 'risk'
                WHEN (last_active_at IS NOT NULL AND last_active_at <= DATE_SUB(NOW(), INTERVAL 30 DAY))
                  OR (last_visit_at IS NOT NULL AND last_visit_at <= DATE_SUB(NOW(), INTERVAL 30 DAY)) THEN 'dormant'
                WHEN stage = 'intent' THEN 'new_deal'
                WHEN created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) AND stage = 'new' THEN 'new'
                WHEN stage = 'regular' THEN 'regular'
                ELSE pool
            END,
            updated_at = NOW()
            WHERE store_id = ?
            """;

        int rows = jdbc.update(sql, storeId);
        log.debug("门店 {} 客户池分类更新 {} 条", storeId, rows);
        return rows;
    }
}
