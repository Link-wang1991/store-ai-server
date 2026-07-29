package com.storeai.home.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.util.CurrentUser;
import com.storeai.task.service.TaskTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
    private final TaskTraceService taskTraceService;

    public Map<String, Object> overview() {
        List<Map<String, Object>> customers = listCustomers();
        // 首页和“我的任务”共用同一条 TaskTraceService，避免卡片上的来源、客户和
        // 知识依据与任务页出现两个版本。
        List<Map<String, Object>> tasks = taskTraceService.listOpenForCurrentEmployee();
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

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
