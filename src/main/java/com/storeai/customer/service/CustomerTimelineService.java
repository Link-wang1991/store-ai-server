package com.storeai.customer.service;

import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户互动时间线。
 * 统一封装 interactions 表的写入与查询。
 */
@Service
@RequiredArgsConstructor
public class CustomerTimelineService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    public void addInteraction(String customerId, String type, String content) {
        if (customerId == null || customerId.isBlank()) return;
        jdbc.update(
            "INSERT INTO interactions (id, store_id, customer_id, employee_id, type, content, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), cur.storeId(), customerId,
            cur.employeeId(), type, content, OffsetDateTime.now().toString());
    }

    public List<Map<String, Object>> getTimeline(String customerId) {
        return jdbc.queryForList(
            "SELECT i.*, e.name as employee_name FROM interactions i " +
            "LEFT JOIN employees e ON e.id = i.employee_id " +
            "WHERE i.customer_id = ? AND i.store_id = ? " +
            "ORDER BY i.created_at DESC LIMIT 50",
            customerId, cur.storeId());
    }
}
