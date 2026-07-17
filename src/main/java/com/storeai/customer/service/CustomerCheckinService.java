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
 * 客户到店签到。
 * 记录互动时间线，并自动推送服务前简报。
 */
@Service
@RequiredArgsConstructor
public class CustomerCheckinService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final CustomerBriefService customerBriefService;
    private final CustomerTimelineService customerTimelineService;

    public Map<String, Object> checkin(String customerId, String note) {
        // 校验客户归属
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM customers WHERE id = ? AND store_id = ?",
            Integer.class, customerId, cur.storeId());
        if (count == null || count == 0) {
            throw new com.storeai.common.exception.BizException("客户不存在");
        }

        // 更新客户最近到店时间
        jdbc.update(
            "UPDATE customers SET last_visit_at = ?, last_active_at = ?, updated_at = NOW() WHERE id = ?",
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString(), customerId);

        // 记录互动时间线
        customerTimelineService.addInteraction(customerId, "checkin",
            "客户到店签到" + (note == null || note.isBlank() ? "" : "：" + note));

        // 生成简报
        Map<String, Object> brief = customerBriefService.generateBrief(customerId);

        // 查询今日待办
        List<Map<String, Object>> todayTasks = jdbc.queryForList(
            "SELECT id, title, type, status FROM tasks WHERE store_id = ? AND assigned_to = ? AND status != 'done' AND status != 'canceled' AND (due_at IS NULL OR DATE(due_at) = CURDATE()) ORDER BY created_at DESC LIMIT 10",
            cur.storeId(), cur.employeeId());

        return Map.of(
            "customer_id", customerId,
            "checked_in_at", OffsetDateTime.now().toString(),
            "brief", brief,
            "today_tasks", todayTasks
        );
    }
}
