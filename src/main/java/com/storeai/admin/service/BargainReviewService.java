package com.storeai.admin.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 议价复盘：汇总会谈中「成交推进/异议处理」维度的表现，帮助店长识别议价能力短板。
 * 数据来源：meeting_analysis.deal_advancing_score / decision_barriers / missed_opportunities。
 */
@Service
@RequiredArgsConstructor
public class BargainReviewService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    /** 议价复盘列表（含成交推进得分、决策障碍、错失机会）。 */
    public List<Map<String, Object>> list() {
        if (!cur.isAdmin()) throw BizException.forbidden();
        return jdbc.queryForList("""
            SELECT ma.id, ma.meeting_id, ma.deal_advancing_score,
                   ma.decision_barriers, ma.missed_opportunities,
                   m.customer_name, m.employee_name, m.scene, ma.updated_at AS reviewed_at
            FROM meeting_analysis ma
            JOIN meetings m ON m.id = ma.meeting_id AND m.store_id = ma.store_id
            WHERE ma.store_id = ?
              AND (ma.deal_advancing_score IS NOT NULL OR ma.decision_barriers IS NOT NULL OR ma.missed_opportunities IS NOT NULL)
            ORDER BY COALESCE(ma.deal_advancing_score, 0) ASC, ma.updated_at DESC
            LIMIT 200
            """, cur.storeId());
    }

    /** 议价维度汇总。 */
    public Map<String, Object> summary() {
        String storeId = cur.storeId();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", jdbc.queryForObject("""
            SELECT COUNT(*) FROM meeting_analysis ma
            JOIN meetings m ON m.id = ma.meeting_id AND m.store_id = ma.store_id
            WHERE ma.store_id = ? AND (ma.deal_advancing_score IS NOT NULL OR ma.decision_barriers IS NOT NULL)
            """, Integer.class, storeId));
        result.put("low_score_count", jdbc.queryForObject("""
            SELECT COUNT(*) FROM meeting_analysis ma
            JOIN meetings m ON m.id = ma.meeting_id AND m.store_id = ma.store_id
            WHERE ma.store_id = ? AND ma.deal_advancing_score IS NOT NULL AND ma.deal_advancing_score < 50
            """, Integer.class, storeId));
        result.put("has_barrier_count", jdbc.queryForObject("""
            SELECT COUNT(*) FROM meeting_analysis ma
            JOIN meetings m ON m.id = ma.meeting_id AND m.store_id = ma.store_id
            WHERE ma.store_id = ? AND ma.decision_barriers IS NOT NULL AND ma.decision_barriers != ''
            """, Integer.class, storeId));
        return result;
    }
}
