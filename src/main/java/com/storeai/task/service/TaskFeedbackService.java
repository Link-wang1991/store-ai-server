package com.storeai.task.service;

import com.storeai.common.util.CurrentUser;
import com.storeai.customer.service.CustomerTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务结果反馈驱动下一步。
 * 员工完成任务时记录最小结果，系统根据结果自动决定下一步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskFeedbackService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final CustomerTimelineService customerTimelineService;

    public Map<String, Object> complete(String taskId, String outcome, String note) {
        // 1. 读取任务
        Map<String, Object> task = jdbc.queryForMap(
            "SELECT * FROM tasks WHERE id = ? AND store_id = ?", taskId, cur.storeId());

        String status = (String) task.get("status");
        if ("done".equals(status)) {
            throw new RuntimeException("任务已完成");
        }
        String assignedTo = (String) task.get("assigned_to");
        if (!cur.isAdmin() && (assignedTo == null || !assignedTo.equals(cur.employeeId()))) {
            throw com.storeai.common.exception.BizException.forbidden("只有任务负责人可以提交结果");
        }

        String taskType = (String) task.get("type");
        if ("memory_confirm".equals(taskType)) {
            throw com.storeai.common.exception.BizException.badRequest("客户记忆请通过“确认 / 修正记忆”操作完成，不可直接标记完成");
        }
        String title = (String) task.get("title");
        String content = (String) task.get("content");
        String customerId = task.get("customer_id") == null
            ? extractCustomerId(content) : String.valueOf(task.get("customer_id"));

        OutcomePolicy policy = outcomePolicy(outcome);
        if (policy == null) {
            throw com.storeai.common.exception.BizException.badRequest("不支持的任务结果，请重新选择");
        }

        // 2. 记录“动作已完成”与“业务结果是否已验证”。两者不能混用：例如“已预约”
        // 只说明约到了时间，不能被经营看板当作到店或成交。
        String feedback = formatFeedback(outcome, note);
        jdbc.update(
            "UPDATE tasks SET status = 'done', feedback = ?, business_outcome_status = ?, result_code = ?, " +
                "result_detail = ?, result_recorded_at = NOW(), requires_result_verification = ?, " +
                "next_follow_at = ?, updated_at = NOW() WHERE id = ? AND store_id = ?",
            feedback, policy.businessStatus(), outcome, cleanNote(note), policy.requiresVerification() ? 1 : 0,
            policy.followAt(), taskId, cur.storeId());

        // 3. 根据结果驱动下一步
        String nextAction = switch (outcome) {
            case "scheduled" -> {
                createResultVerification(task, "核验客户是否到店：" + title, note, 1);
                yield "已记录预约动作，并生成到店结果核验任务；到店前不计为业务成功。";
            }
            case "accepted" -> {
                createResultVerification(task, "核验客户接受后的实际进展：" + title, note, 2);
                yield "已记录客户接受，并生成实际进展核验任务。";
            }
            case "concern" -> {
                recordMemory(customerId, "concerns", note);
                createResultVerification(task, "核验顾虑是否解决：" + title, note, 1);
                yield "已记录顾虑并生成解决结果核验任务。";
            }
            case "escalate" -> {
                escalateToManager(task, note);
                createResultVerification(task, "核验店长处理结果：" + title, note, 1);
                yield "已升级给店长，并生成处理结果核验任务。";
            }
            case "wrong_info" -> {
                createResultVerification(task, "核验信息修正后结果：" + title, note, 0);
                yield "已生成信息修正结果核验任务。";
            }
            case "not_interested", "no_reply" -> {
                createFollowup(task, "再次唤醒：" + title, note, 7);
                yield "已记录本次结果并生成后续唤醒任务；当前不计为业务成功。";
            }
            case "arrived" -> "已确认客户实际到店，业务结果已验证。";
            case "deal_closed" -> "已确认成交结果，业务结果已验证。";
            case "risk_resolved" -> "已确认风险处理结果，业务结果已验证。";
            case "no_show" -> {
                createFollowup(task, "未到店后续跟进：" + title, note, 2);
                yield "已确认未到店，并生成后续跟进任务。";
            }
            default -> "已记录结果";
        };

        // 记录互动时间线
        if (customerId != null) {
            customerTimelineService.addInteraction(customerId, "task_complete",
                "任务完成：" + title + "，结果：" + feedback);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId);
        result.put("outcome", outcome);
        result.put("business_outcome_status", policy.businessStatus());
        result.put("requires_result_verification", policy.requiresVerification());
        result.put("next_action", nextAction);
        return result;
    }

    private String formatFeedback(String outcome, String note) {
        String label = switch (outcome) {
            case "accepted" -> "已接受";
            case "concern" -> "仍有顾虑";
            case "scheduled" -> "已预约";
            case "not_interested" -> "暂不考虑";
            case "no_reply" -> "未回复";
            case "escalate" -> "需要升级";
            case "wrong_info" -> "信息有误";
            case "arrived" -> "已实际到店";
            case "deal_closed" -> "已确认成交";
            case "risk_resolved" -> "风险已解决";
            case "no_show" -> "预约未到店";
            default -> outcome;
        };
        return label + (note == null || note.isBlank() ? "" : " | " + note);
    }

    private void createFollowup(Map<String, Object> task, String title, String content, int daysLater) {
        String storeId = (String) task.get("store_id");
        String assignedTo = (String) task.get("assigned_to");
        String customerId = task.get("customer_id") == null ? extractCustomerId((String) task.get("content"))
            : String.valueOf(task.get("customer_id"));
        String sourceMeetingId = task.get("source_meeting_id") == null ? null : String.valueOf(task.get("source_meeting_id"));
        if (assignedTo == null) assignedTo = cur.employeeId();
        OffsetDateTime dueAt = daysLater >= 0 ? OffsetDateTime.now().plusDays(daysLater) : null;

        jdbc.update(
            "INSERT INTO tasks (id, store_id, customer_id, title, content, type, status, assigned_to, created_by, due_at, source_type, source_id, source_meeting_id, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, 'followup', 'todo', ?, ?, ?, 'task_feedback', ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId, customerId,
            title.length() > 200 ? title.substring(0, 200) : title,
            content == null ? "" : content,
            assignedTo, cur.employeeId(),
            dueAt == null ? null : dueAt.toString(),
            task.get("id"), sourceMeetingId,
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
    }

    /**
     * 结果核验是正式待办，不是页面上的一段提示。只有核验任务记录到店、成交或风险
     * 已解决后，才会把业务结果标为 verified。
     */
    private void createResultVerification(Map<String, Object> task, String title, String content, int daysLater) {
        String storeId = (String) task.get("store_id");
        String assignedTo = (String) task.get("assigned_to");
        String customerId = task.get("customer_id") == null ? extractCustomerId((String) task.get("content"))
            : String.valueOf(task.get("customer_id"));
        if (assignedTo == null) assignedTo = cur.employeeId();
        OffsetDateTime dueAt = OffsetDateTime.now().plusDays(Math.max(0, daysLater));
        jdbc.update(
            "INSERT INTO tasks (id, store_id, customer_id, title, content, type, status, assigned_to, created_by, due_at, " +
                "source_type, source_id, source_meeting_id, business_outcome_status, requires_result_verification, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'result_verification', 'todo', ?, ?, ?, 'task_feedback', ?, ?, 'pending', 1, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId, customerId,
            title.length() > 200 ? title.substring(0, 200) : title,
            content == null ? "" : content, assignedTo, cur.employeeId(), dueAt.toString(), task.get("id"),
            task.get("source_meeting_id"), OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
    }

    private OutcomePolicy outcomePolicy(String outcome) {
        if (outcome == null) return null;
        return switch (outcome) {
            case "arrived", "deal_closed", "risk_resolved" -> new OutcomePolicy("verified", false, null);
            case "scheduled", "accepted", "concern", "escalate", "wrong_info" ->
                new OutcomePolicy("pending_verification", true, OffsetDateTime.now().plusDays(1).toString());
            case "no_reply", "not_interested", "no_show" ->
                new OutcomePolicy("pending_reactivation", true, OffsetDateTime.now().plusDays(7).toString());
            default -> null;
        };
    }

    private String cleanNote(String note) {
        if (note == null) return null;
        String value = note.trim();
        return value.isBlank() ? null : value.length() > 2_000 ? value.substring(0, 2_000) : value;
    }

    private record OutcomePolicy(String businessStatus, boolean requiresVerification, String followAt) {}

    private void recordMemory(String customerId, String key, String value) {
        if (customerId == null || value == null || value.isBlank()) return;
        jdbc.update(
            "INSERT INTO memory_items (id, store_id, customer_id, employee_id, scope, `key`, value, confidence, status, source_type, source_id, created_at) " +
            "VALUES (?, ?, ?, ?, 'customer', ?, ?, 'medium', 'confirmed', 'task_feedback', ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), cur.storeId(), customerId,
            cur.employeeId(), key, value, "task_" + UUID.randomUUID().toString().replace("-", ""),
            OffsetDateTime.now().toString());
    }

    private void escalateToManager(Map<String, Object> task, String note) {
        String title = (String) task.get("title");
        jdbc.update(
            "INSERT INTO pending_questions (id, store_id, employee_id, question, ai_suggestion, category, risk_level, status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, '任务升级', 'L3', 'pending', ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), cur.storeId(), cur.employeeId(),
            "【任务升级】" + title, note == null ? "" : note,
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
    }

    private String extractCustomerId(String content) {
        if (content == null) return null;
        // 简单从内容中匹配 customerId=xxx
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("customerId[=:](\\w+)").matcher(content);
        return m.find() ? m.group(1) : null;
    }
}
