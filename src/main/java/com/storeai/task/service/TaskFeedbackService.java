package com.storeai.task.service;

import com.storeai.common.util.CurrentUser;
import com.storeai.customer.service.CustomerTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
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

        String taskType = (String) task.get("type");
        String title = (String) task.get("title");
        String content = (String) task.get("content");
        String assignedTo = (String) task.get("assigned_to");
        String customerId = extractCustomerId(content);

        // 2. 记录反馈
        String feedback = formatFeedback(outcome, note);
        jdbc.update(
            "UPDATE tasks SET status = 'done', feedback = ?, updated_at = ? WHERE id = ?",
            feedback, OffsetDateTime.now().toString(), taskId);

        // 3. 根据结果驱动下一步
        String nextAction = switch (outcome) {
            case "scheduled" -> {
                createFollowup(task, "客户已预约：" + title, note, 1);
                yield "已生成确认到店前的提醒任务";
            }
            case "accepted" -> {
                createFollowup(task, "跟进成交：" + title, note, 2);
                yield "已生成推进成交任务";
            }
            case "concern" -> {
                recordMemory(customerId, "concerns", note);
                createFollowup(task, "处理客户顾虑：" + title, note, 1);
                yield "已记录顾虑并生成跟进任务";
            }
            case "escalate" -> {
                escalateToManager(task, note);
                yield "已升级给店长";
            }
            case "wrong_info" -> {
                createFollowup(task, "修正后重试：" + title, note, 0);
                yield "已生成修正任务";
            }
            case "not_interested", "no_reply" -> {
                createFollowup(task, "再次唤醒：" + title, note, 7);
                yield "已生成后续唤醒任务";
            }
            default -> "已记录结果";
        };

        // 记录互动时间线
        if (customerId != null) {
            customerTimelineService.addInteraction(customerId, "task_complete",
                "任务完成：" + title + "，结果：" + feedback);
        }

        return Map.of(
            "task_id", taskId,
            "outcome", outcome,
            "next_action", nextAction
        );
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
            default -> outcome;
        };
        return label + (note == null || note.isBlank() ? "" : " | " + note);
    }

    private void createFollowup(Map<String, Object> task, String title, String content, int daysLater) {
        String storeId = (String) task.get("store_id");
        String assignedTo = (String) task.get("assigned_to");
        if (assignedTo == null) assignedTo = cur.employeeId();
        OffsetDateTime dueAt = daysLater >= 0 ? OffsetDateTime.now().plusDays(daysLater) : null;

        jdbc.update(
            "INSERT INTO tasks (id, store_id, title, content, type, status, assigned_to, created_by, due_at, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, 'followup', 'todo', ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId,
            title.length() > 200 ? title.substring(0, 200) : title,
            content == null ? "" : content,
            assignedTo, cur.employeeId(),
            dueAt == null ? null : dueAt.toString(),
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
    }

    private void recordMemory(String customerId, String key, String value) {
        if (customerId == null || value == null || value.isBlank()) return;
        jdbc.update(
            "INSERT INTO memory_items (id, store_id, customer_id, employee_id, scope, `key`, value, confidence, source_type, source_id, created_at) " +
            "VALUES (?, ?, ?, ?, 'customer', ?, ?, 'medium', 'task_feedback', ?, ?)",
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
