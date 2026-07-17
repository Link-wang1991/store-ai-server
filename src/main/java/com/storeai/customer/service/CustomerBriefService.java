package com.storeai.customer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.ai.AiAdapter;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户到店前的 AI 服务简报。
 * 整合客户档案、记忆、最近会谈、未完成任务，生成 30 秒可读简报。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerBriefService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final AiAdapter aiAdapter;
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> generateBrief(String customerId) {
        validateAccess(customerId);

        Map<String, Object> customer = jdbc.queryForMap(
            "SELECT * FROM customers WHERE id = ? AND store_id = ?", customerId, cur.storeId());

        List<Map<String, Object>> memories = jdbc.queryForList(
            "SELECT `key`, value, confidence, created_at FROM memory_items " +
            "WHERE customer_id = ? AND store_id = ? ORDER BY created_at DESC LIMIT 20", customerId, cur.storeId());

        Map<String, Object> latestMeeting = querySingle(
            "SELECT m.*, ma.summary, ma.explicit_needs, ma.implicit_needs, ma.emotional_needs, " +
            "ma.decision_barriers, ma.followup_goal, ma.suggested_script " +
            "FROM meetings m LEFT JOIN meeting_analysis ma ON ma.meeting_id = m.id " +
            "WHERE m.customer_id = ? AND m.store_id = ? ORDER BY m.ended_at DESC LIMIT 1",
            customerId, cur.storeId());

        List<Map<String, Object>> pendingTasks = jdbc.queryForList(
            "SELECT title, content, type, status, due_at FROM tasks " +
            "WHERE assigned_to = ? AND store_id = ? AND status != 'done' AND status != 'canceled' " +
            "ORDER BY due_at ASC LIMIT 10",
            cur.employeeId(), cur.storeId());

        String prompt = buildPrompt(customer, memories, latestMeeting, pendingTasks);
        String aiResult = aiAdapter.callJson(buildSystem(), prompt);

        Map<String, Object> brief;
        if (aiResult != null) {
            try {
                String json = extractJson(aiResult);
                brief = mapper.readValue(json, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("简报 JSON 解析失败，使用兜底结构: {}", e.getMessage());
                brief = fallbackBrief();
            }
        } else {
            brief = fallbackBrief();
        }

        brief.put("customer_id", customerId);
        brief.put("generated_at", java.time.OffsetDateTime.now().toString());
        return brief;
    }

    private void validateAccess(String customerId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM customers WHERE id = ? AND store_id = ?", Integer.class, customerId, cur.storeId());
        if (count == null || count == 0) {
            throw new com.storeai.common.exception.BizException("客户不存在");
        }
        if (!cur.isAdmin()) {
            Integer own = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customers WHERE id = ? AND store_id = ? AND assigned_to = ?",
                Integer.class, customerId, cur.storeId(), cur.employeeId());
            if (own == null || own == 0) {
                throw new com.storeai.common.exception.BizException("无权查看该客户");
            }
        }
    }

    private Map<String, Object> querySingle(String sql, Object... args) {
        try {
            return jdbc.queryForMap(sql, args);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String buildSystem() {
        return "你是门店 AI 经营助手，擅长在客户到店前为服务员工生成 30 秒服务简报。"
            + "请基于提供的客户档案、AI 记忆、最近会谈和未完成任务，输出 JSON。"
            + "字段说明：\n"
            + "lastService: 最近一次服务/结果（一句话）\n"
            + "coreNeeds: 本次可能的核心需求（数组）\n"
            + "pendingItems: 未完成事项或历史承诺（数组）\n"
            + "suggestedQuestions: 建议重点追问的问题（数组）\n"
            + "risks: 风险、禁忌和注意事项（数组，无则空数组）\n"
            + "openingScript: 一句可以直接使用的开场话术（字符串）";
    }

    private String buildPrompt(Map<String, Object> customer,
                               List<Map<String, Object>> memories,
                               Map<String, Object> latestMeeting,
                               List<Map<String, Object>> pendingTasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 客户档案 ===\n");
        sb.append("姓名：").append(customer.getOrDefault("name", "")).append("\n");
        sb.append("阶段：").append(customer.getOrDefault("stage", "")).append("\n");
        sb.append("池：").append(customer.getOrDefault("pool", "")).append("\n");
        sb.append("标签：").append(customer.getOrDefault("tags", "")).append("\n");
        sb.append("顾虑：").append(customer.getOrDefault("concerns", "")).append("\n");
        sb.append("画像：").append(customer.getOrDefault("portrait", "")).append("\n\n");

        sb.append("=== AI 记忆 ===\n");
        if (memories.isEmpty()) sb.append("（暂无）\n");
        else {
            for (var m : memories) {
                sb.append("- [").append(m.get("key")).append("] ")
                  .append(m.get("value")).append("（可信度：").append(m.get("confidence")).append("）\n");
            }
        }
        sb.append("\n=== 最近会谈 ===\n");
        if (latestMeeting.isEmpty()) sb.append("（暂无）\n");
        else {
            sb.append("摘要：").append(latestMeeting.getOrDefault("summary", "")).append("\n");
            sb.append("明确需求：").append(latestMeeting.getOrDefault("explicit_needs", "")).append("\n");
            sb.append("隐性需求：").append(latestMeeting.getOrDefault("implicit_needs", "")).append("\n");
            sb.append("情绪需求：").append(latestMeeting.getOrDefault("emotional_needs", "")).append("\n");
            sb.append("决策障碍：").append(latestMeeting.getOrDefault("decision_barriers", "")).append("\n");
            sb.append("跟进目标：").append(latestMeeting.getOrDefault("followup_goal", "")).append("\n");
        }
        sb.append("\n=== 未完成任务 ===\n");
        if (pendingTasks.isEmpty()) sb.append("（暂无）\n");
        else {
            for (var t : pendingTasks) {
                sb.append("- ").append(t.get("title")).append("：").append(t.get("content")).append("\n");
            }
        }
        sb.append("\n请输出 JSON 简报。");
        return sb.toString();
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return text;
    }

    private Map<String, Object> fallbackBrief() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastService", "");
        m.put("coreNeeds", List.of());
        m.put("pendingItems", List.of());
        m.put("suggestedQuestions", List.of());
        m.put("risks", List.of());
        m.put("openingScript", "您好，欢迎光临，今天想重点了解哪方面？");
        return m;
    }
}
