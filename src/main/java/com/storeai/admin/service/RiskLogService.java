package com.storeai.admin.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 风险复盘：汇总门店风险日志，供老板/店长查看与闭环处理。
 */
@Service
@RequiredArgsConstructor
public class RiskLogService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    /** 风险日志列表（管理者看全店；普通员工只看自己产生的）。 */
    public List<Map<String, Object>> list(String status) {
        StringBuilder sql = new StringBuilder(
            "SELECT r.*, e.name AS handled_by_name FROM risk_logs r " +
            "LEFT JOIN employees e ON e.id = r.handled_by " +
            "WHERE r.store_id = ?");
        if (!cur.isAdmin()) {
            sql.append(" AND r.employee_id = ?");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = ?");
        }
        sql.append(" ORDER BY r.created_at DESC LIMIT 200");

        List<Object> params = new java.util.ArrayList<>();
        params.add(cur.storeId());
        if (!cur.isAdmin()) params.add(cur.employeeId());
        if (status != null && !status.isBlank()) params.add(status);
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /** 风险汇总统计（按风险级别 L1-L4 与状态）。 */
    public Map<String, Object> summary() {
        String storeId = cur.storeId();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("open", count(storeId, "status = 'open'"));
        result.put("handled", count(storeId, "status = 'handled'"));
        result.put("l1", count(storeId, "level = 'L1'"));
        result.put("l2", count(storeId, "level = 'L2'"));
        result.put("l3", count(storeId, "level = 'L3'"));
        result.put("l4", count(storeId, "level = 'L4'"));
        return result;
    }

    /** 处理一条风险：记录处理人与结论。 */
    public Map<String, Object> handle(String id, String resolution) {
        validateStore(id);
        jdbc.update(
            "UPDATE risk_logs SET status = 'handled', handled_by = ?, resolution = ?, updated_at = NOW() WHERE id = ?",
            cur.employeeId(), resolution == null || resolution.isBlank() ? null : resolution.trim(), id);
        return jdbc.queryForMap("SELECT * FROM risk_logs WHERE id = ?", id);
    }

    private int count(String storeId, String condition) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM risk_logs WHERE store_id = ? AND " + condition,
            Integer.class, storeId);
        return c == null ? 0 : c;
    }

    private void validateStore(String id) {
        Integer cnt = jdbc.queryForObject(
            "SELECT COUNT(*) FROM risk_logs WHERE id = ? AND store_id = ?",
            Integer.class, id, cur.storeId());
        if (cnt == null || cnt == 0) throw BizException.notFound("风险记录");
    }
}
