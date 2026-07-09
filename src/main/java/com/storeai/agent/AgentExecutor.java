package com.storeai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 行动执行器（对应前端 lib/agent/actions.ts + executor.ts）
 * 从 AI 回答中解析结构化行动并执行。
 * 标记格式：【AGENT_ACTION】JSON【/AGENT_ACTION】
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutor {

    private final CurrentUser cur;
    private final JdbcTemplate jdbc;

    private static final String START_MARKER = "【AGENT_ACTION】";
    private static final String END_MARKER = "【/AGENT_ACTION】";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析字符串中的 Agent 行动标记。
     */
    public List<AgentAction> parseActions(String text) {
        var actions = new ArrayList<AgentAction>();
        if (text == null) return actions;

        int cursor = 0;
        while (true) {
            int start = text.indexOf(START_MARKER, cursor);
            if (start < 0) break;
            int end = text.indexOf(END_MARKER, start + START_MARKER.length());
            if (end < 0) break;

            var json = text.substring(start + START_MARKER.length(), end).trim();
            try {
                var map = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
                if (map.containsKey("type") && map.containsKey("payload")) {
                    var payload = new java.util.LinkedHashMap<String, String>();
                    if (map.get("payload") instanceof Map<?, ?> p) {
                        for (var e : p.entrySet()) {
                            payload.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                        }
                    }
                    actions.add(new AgentAction(
                        String.valueOf(map.get("type")),
                        String.valueOf(map.getOrDefault("reason", "")),
                        payload
                    ));
                }
            } catch (Exception e) {
                log.warn("Agent action JSON 解析失败: {}", e.getMessage());
            }
            cursor = end + END_MARKER.length();
        }
        return actions;
    }

    /**
     * 从文本中移除 Agent 行动标记。
     */
    public String stripActions(String text) {
        if (text == null) return null;
        return text.replaceAll(START_MARKER + "[\\s\\S]*?" + END_MARKER + "\\n?", "").trim();
    }

    /**
     * 执行一组 Agent 行动。
     */
    public List<ActionResult> execute(List<AgentAction> actions, String customerId) {
        var results = new ArrayList<ActionResult>();
        if (actions.isEmpty()) return results;

        boolean isAdmin = "owner".equals(cur.role()) || "manager".equals(cur.role());

        for (var action : actions) {
            try {
                // 权限检查
                if (!isAdmin) {
                    if ("update_customer_stage".equals(action.type()) || "add_customer_tag".equals(action.type())) {
                        results.add(new ActionResult(action.type(), false, "仅店长/老板可修改客户信息"));
                        continue;
                    }
                    if ("create_task".equals(action.type())
                        && action.payload().containsKey("assignee")
                        && !"self".equals(action.payload().get("assignee"))) {
                        results.add(new ActionResult(action.type(), false, "仅店长/老板可分配任务给他人"));
                        continue;
                    }
                }

                switch (action.type()) {
                    case "create_task" -> results.add(executeCreateTask(action));
                    case "update_customer_stage" -> results.add(executeUpdateStage(action, customerId));
                    case "add_customer_tag" -> results.add(executeAddTag(action, customerId));
                    case "suggest_followup" -> results.add(executeSuggestFollowup(action, customerId));
                    case "trigger_opportunity" -> results.add(executeTriggerOpportunity(action, customerId));
                    case "alert_manager" -> results.add(executeAlertManager(action));
                    default -> results.add(new ActionResult(action.type(), false, "不支持的行动类型"));
                }
            } catch (Exception e) {
                results.add(new ActionResult(action.type(), false, "执行失败: " + e.getMessage()));
            }
        }
        return results;
    }

    private ActionResult executeCreateTask(AgentAction action) {
        var p = action.payload();
        var title = p.getOrDefault("title", "");
        if (title.isBlank()) return new ActionResult("create_task", false, "缺少任务标题");

        var assignee = p.getOrDefault("assignee", "self");
        var targetId = "self".equals(assignee) ? cur.employeeId() : p.get("assignedToId");

        var id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
            INSERT INTO tasks (id, store_id, title, content, task_type, assigned_to, deadline, status, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'todo', ?, NOW())
            """,
            id, cur.storeId(), title, action.reason(),
            p.getOrDefault("task_type", "客户跟进"),
            targetId != null ? targetId : cur.employeeId(),
            p.getOrDefault("deadline", null),
            cur.employeeId()
        );
        return new ActionResult("create_task", true,
            "已创建任务\"" + title + "\"" + (p.containsKey("deadline") ? "，截止" + p.get("deadline") : ""));
    }

    private ActionResult executeUpdateStage(AgentAction action, String customerId) {
        if (customerId == null) return new ActionResult("update_customer_stage", false, "未关联客户");
        var stage = action.payload().get("stage");
        if (stage == null) return new ActionResult("update_customer_stage", false, "缺少阶段值");

        jdbc.update("UPDATE customers SET stage = ?, notes = CONCAT(IFNULL(notes,''), '【AI建议更新阶段】') WHERE id = ? AND store_id = ?",
            stage, customerId, cur.storeId());
        return new ActionResult("update_customer_stage", true, "已建议更新客户阶段为: " + stage);
    }

    private ActionResult executeAddTag(AgentAction action, String customerId) {
        if (customerId == null) return new ActionResult("add_customer_tag", false, "未关联客户");
        var tag = action.payload().get("tag");
        if (tag == null || tag.isBlank()) return new ActionResult("add_customer_tag", false, "缺少标签名");

        jdbc.update("UPDATE customers SET tags = JSON_ARRAY_APPEND(IFNULL(tags,'[]'), '$', ?) WHERE id = ? AND store_id = ? AND (tags IS NULL OR NOT JSON_CONTAINS(tags, ?, '$'))",
            tag, customerId, cur.storeId(), "\"" + tag + "\"");
        return new ActionResult("add_customer_tag", true, "已添加标签\"" + tag + "\"");
    }

    private ActionResult executeSuggestFollowup(AgentAction action, String customerId) {
        if (customerId == null) return new ActionResult("suggest_followup", false, "未关联客户");
        var method = action.payload().getOrDefault("method", "微信");
        var note = action.payload().getOrDefault("note", action.reason());

        var id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
            INSERT INTO tasks (id, store_id, title, content, task_type, assigned_to, status, created_by, created_at)
            VALUES (?, ?, ?, ?, '客户跟进', ?, 'todo', ?, NOW())
            """,
            id, cur.storeId(),
            "跟进" + (note.length() > 20 ? note.substring(0, 20) : note),
            "通过" + method + "跟进: " + note,
            cur.employeeId(), cur.employeeId()
        );
        return new ActionResult("suggest_followup", true, "已创建跟进建议，通过" + method + "跟进");
    }

    private ActionResult executeTriggerOpportunity(AgentAction action, String customerId) {
        if (customerId == null) return new ActionResult("trigger_opportunity", false, "未关联客户");
        var type = action.payload().get("opportunity_type");
        if (type == null) return new ActionResult("trigger_opportunity", false, "缺少机会类型");

        var id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
            INSERT INTO opportunities (id, store_id, customer_id, employee_id, type, status, source, note, created_at)
            VALUES (?, ?, ?, ?, ?, 'open', 'ai_agent', ?, NOW())
            """,
            id, cur.storeId(), customerId, cur.employeeId(), type, action.reason()
        );
        return new ActionResult("trigger_opportunity", true, "已创建增长机会(" + type + ")");
    }

    private ActionResult executeAlertManager(AgentAction action) {
        var reason = action.payload().getOrDefault("reason", action.reason());
        var id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
            INSERT INTO pending_questions (id, store_id, employee_id, question, ai_suggestion, category, risk_level, status, created_at)
            VALUES (?, ?, ?, ?, ?, '其他问题', 'L3', 'pending', NOW())
            """,
            id, cur.storeId(), cur.employeeId(),
            "【Agent升级】" + reason,
            action.payload().getOrDefault("detail", "")
        );
        return new ActionResult("alert_manager", true, "已升级给管理者，请在待确认问题中查看");
    }

    public record AgentAction(String type, String reason, Map<String, String> payload) {}
    public record ActionResult(String type, boolean success, String detail) {}
}
