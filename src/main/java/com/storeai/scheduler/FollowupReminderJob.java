package com.storeai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每日跟进提醒任务生成器。
 * 每天早上 9:00 扫描 next_follow_at 为当天的客户，自动创建 followup 任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowupReminderJob {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 9 * * ?")
    public void run() {
        log.info("开始生成每日跟进提醒任务");
        List<String> storeIds = jdbc.queryForList("SELECT DISTINCT store_id FROM customers WHERE store_id IS NOT NULL", String.class);

        int total = 0;
        for (String storeId : storeIds) {
            total += processStore(storeId);
        }
        log.info("每日跟进提醒任务生成完成，共 {} 条", total);
    }

    private int processStore(String storeId) {
        List<Map<String, Object>> customers = jdbc.queryForList("""
            SELECT c.id, c.name, c.assigned_to
            FROM customers c
            WHERE c.store_id = ?
              AND DATE(c.next_follow_at) = CURDATE()
              AND NOT EXISTS (
                  SELECT 1 FROM tasks t
                  WHERE t.store_id = c.store_id
                    AND t.type = 'followup'
                    AND t.status NOT IN ('done', 'canceled')
                    AND DATE(t.created_at) = CURDATE()
                    AND t.assigned_to = c.assigned_to
              )
            """, storeId);

        int count = 0;
        for (Map<String, Object> c : customers) {
            String customerId = (String) c.get("id");
            String customerName = (String) c.get("name");
            String assignedTo = (String) c.get("assigned_to");
            if (assignedTo == null) {
                assignedTo = findAnyEmployee(storeId);
            }
            if (assignedTo == null) continue;

            jdbc.update("""
                INSERT INTO tasks (id, store_id, title, content, type, status, assigned_to, created_by, due_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'followup', 'todo', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString().replace("-", ""),
                storeId,
                "今日待跟进：" + (customerName == null ? "" : customerName),
                "客户今日需跟进，来源：next_follow_at",
                assignedTo,
                assignedTo,
                OffsetDateTime.now().toString(),
                OffsetDateTime.now().toString(),
                OffsetDateTime.now().toString()
            );
            count++;
        }
        return count;
    }

    private String findAnyEmployee(String storeId) {
        try {
            return jdbc.queryForObject(
                "SELECT id FROM employees WHERE store_id = ? AND status = 'active' LIMIT 1",
                String.class, storeId);
        } catch (Exception e) {
            return null;
        }
    }
}
