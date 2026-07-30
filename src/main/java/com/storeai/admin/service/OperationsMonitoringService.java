package com.storeai.admin.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 店长的异常运营台：汇总真实业务链路异常，不把“页面加载成功”误当成系统健康。 */
@Service
@RequiredArgsConstructor
public class OperationsMonitoringService {
    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    public Map<String, Object> overview() {
        if (!cur.isAdmin()) throw BizException.forbidden();
        String storeId = cur.storeId();
        List<Map<String, Object>> items = new ArrayList<>();
        add(items, count("SELECT COUNT(*) FROM meetings WHERE store_id = ? AND status = 'failed'", storeId), "critical", "会谈处理失败", "录音、转写或分析已最终失败；录音仍可能保存在详情页，可查看错误码后重试。", "/admin/meetings");
        add(items, count("SELECT COUNT(*) FROM meetings WHERE store_id = ? AND status IN ('queued','submitting','transcribing','analyzing') AND updated_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)", storeId), "warning", "会谈处理停滞", "超过 10 分钟未更新的会谈，需要确认 ASR/分析队列或手动重试。", "/admin/meetings");
        add(items, count("SELECT COUNT(*) FROM meetings WHERE store_id = ? AND closure_status IN ('partial_failed','failed')", storeId), "warning", "会谈业务闭环未完成", "报告已生成，但任务、客户记忆或经验审核中至少一项未落库。", "/admin/meetings");
        add(items, count("SELECT COUNT(*) FROM knowledge_documents WHERE store_id = ? AND status = 'active' AND (review_status IN ('needs_review','draft') OR (expires_at IS NOT NULL AND expires_at <= NOW()) OR (review_due_at IS NOT NULL AND review_due_at <= NOW()))", storeId), "warning", "知识资料待复核/已到期", "到期或待复核资料不会参与后续 AI 检索，请在知识库生命周期中处理。", "/admin/knowledge");
        add(items, count("SELECT COUNT(*) FROM tasks WHERE store_id = ? AND status IN ('todo','doing','overdue') AND due_at IS NOT NULL AND due_at < NOW()", storeId), "warning", "逾期增长动作", "客户动作已过截止时间，需重新安排或记录真实结果。", "/admin/tasks");
        add(items, count("SELECT COUNT(*) FROM tasks WHERE store_id = ? AND status = 'done' AND business_outcome_status IN ('pending_verification','pending_reactivation')", storeId), "info", "业务结果待核验", "员工已执行动作，但尚未确认到店、成交、风险处理等实际结果。", "/admin/tasks");
        add(items, count("SELECT COUNT(*) FROM chat_messages WHERE store_id = ? AND generation_mode IN ('fallback','safety_rule') AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)", storeId), "info", "AI 兜底/安全规则回答", "过去 24 小时出现的非模型正常回答；请查看提问记录及知识缺口。", "/admin/chats");

        long critical = items.stream().filter(item -> "critical".equals(item.get("severity"))).mapToLong(item -> ((Number) item.get("count")).longValue()).sum();
        long warning = items.stream().filter(item -> "warning".equals(item.get("severity"))).mapToLong(item -> ((Number) item.get("count")).longValue()).sum();
        long total = items.stream().mapToLong(item -> ((Number) item.get("count")).longValue()).sum();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("critical", critical);
        summary.put("warning", warning);
        summary.put("total", total);
        summary.put("checked_at", java.time.OffsetDateTime.now());
        return Map.of("summary", summary, "items", items);
    }

    private Integer count(String sql, String storeId) {
        Integer result = jdbc.queryForObject(sql, Integer.class, storeId);
        return result == null ? 0 : result;
    }

    private void add(List<Map<String, Object>> items, int count, String severity, String title, String detail, String href) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("severity", severity);
        item.put("title", title);
        item.put("detail", detail);
        item.put("count", count);
        item.put("href", href);
        items.add(item);
    }
}
