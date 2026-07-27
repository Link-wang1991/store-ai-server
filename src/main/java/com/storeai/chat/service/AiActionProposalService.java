package com.storeai.chat.service;

import com.storeai.chat.entity.ChatMessage;
import com.storeai.chat.repository.ChatMessageRepository;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import com.storeai.customer.service.CustomerTimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将 AI 的文字建议转换为“待确认的业务提案”。
 * 这里刻意不复用会自动执行动作的 AgentExecutor：任何创建任务都必须由员工再确认一次。
 */
@Service
@RequiredArgsConstructor
public class AiActionProposalService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final ChatMessageRepository messageRepo;
    private final CustomerTimelineService customerTimelineService;

    @Transactional
    public ActionProposal create(String messageId) {
        ChatMessage message = requireOwnMessage(messageId);
        String customerId = normalize(message.getCustomerId());
        if (customerId == null) throw BizException.badRequest("当前对话未关联客户，无法创建客户跟进待办");

        var existing = findByMessage(messageId);
        if (existing != null) return existing;

        String nextAction = extractNextAction(message.getAiResponse());
        String title = "AI 建议跟进 · " + truncate(nextAction, 64);
        String id = UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime defaultDueAt = OffsetDateTime.now().plusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0);
        String now = OffsetDateTime.now().toString();
        jdbc.update("""
            INSERT INTO ai_action_proposals
            (id, store_id, employee_id, message_id, customer_id, action_type, title, content, assigned_to, priority, due_at, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'followup', ?, ?, ?, 'normal', ?, 'pending', ?, ?)
            """, id, cur.storeId(), cur.employeeId(), messageId, customerId, title, nextAction,
            cur.employeeId(), defaultDueAt.toString(), now, now);
        return findById(id);
    }

    /** 员工确认前可以把 AI 文本修订为真正可执行的任务。 */
    @Transactional
    public ActionProposal update(String proposalId, String title, String content, String assignedTo,
                                 String priority, String dueAt) {
        ActionProposal proposal = requireOwnProposal(proposalId);
        if (!"pending".equals(proposal.status())) throw BizException.badRequest("已处理的建议不能再编辑");
        String normalizedTitle = normalize(title);
        String normalizedContent = normalize(content);
        String normalizedAssignee = normalize(assignedTo);
        String normalizedPriority = normalize(priority);
        if (normalizedTitle == null || normalizedContent == null) throw BizException.badRequest("请补齐待办标题和具体动作");
        if (normalizedTitle.length() > 200 || normalizedContent.length() > 2_000) throw BizException.badRequest("待办内容过长");
        if (!List.of("normal", "high", "urgent").contains(normalizedPriority)) {
            throw BizException.badRequest("优先级只能为 normal、high 或 urgent");
        }
        if (normalizedAssignee == null || !canAssignTo(normalizedAssignee)) {
            throw BizException.forbidden("不能把待办分配给该员工");
        }
        OffsetDateTime parsedDueAt = parseDueAt(dueAt);
        jdbc.update("""
            UPDATE ai_action_proposals
            SET title = ?, content = ?, assigned_to = ?, priority = ?, due_at = ?, updated_at = NOW()
            WHERE id = ? AND store_id = ? AND employee_id = ? AND status = 'pending'
            """, normalizedTitle, normalizedContent, normalizedAssignee, normalizedPriority,
            parsedDueAt.toString(), proposalId, cur.storeId(), cur.employeeId());
        return findById(proposalId);
    }

    @Transactional
    public ActionProposal apply(String proposalId) {
        ActionProposal proposal = requireOwnProposal(proposalId);
        if ("applied".equals(proposal.status())) return proposal;
        if (!"pending".equals(proposal.status())) throw BizException.badRequest("该建议已被放弃，不能再应用");
        if (proposal.assignedTo() == null || proposal.assignedTo().isBlank() || proposal.dueAt() == null || proposal.dueAt().isBlank()) {
            throw BizException.badRequest("请先确认负责人和截止时间，再创建待办");
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        String now = OffsetDateTime.now().toString();
        jdbc.update("""
            INSERT INTO tasks
            (id, store_id, customer_id, title, content, type, status, priority, assigned_to, created_by, due_at,
             source_type, source_id, source_meeting_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'followup', 'todo', ?, ?, ?, ?, 'ai_coach', ?, NULL, ?, ?)
            """, taskId, cur.storeId(), proposal.customerId(), proposal.title(), proposal.content(),
            proposal.priority(), proposal.assignedTo(), cur.employeeId(), proposal.dueAt(), proposal.id(), now, now);
        jdbc.update("""
            UPDATE ai_action_proposals
            SET status = 'applied', applied_task_id = ?, updated_at = NOW()
            WHERE id = ? AND store_id = ? AND employee_id = ? AND status = 'pending'
            """, taskId, proposalId, cur.storeId(), cur.employeeId());
        customerTimelineService.addInteraction(proposal.customerId(), "ai_coach_action_applied",
            "已确认 AI 教练建议并创建待办：" + proposal.title());
        return findById(proposalId);
    }

    public ActionProposal reject(String proposalId) {
        ActionProposal proposal = requireOwnProposal(proposalId);
        if ("pending".equals(proposal.status())) {
            jdbc.update("""
                UPDATE ai_action_proposals SET status = 'rejected', updated_at = NOW()
                WHERE id = ? AND store_id = ? AND employee_id = ?
                """, proposalId, cur.storeId(), cur.employeeId());
        }
        return findById(proposalId);
    }

    public ActionProposal findByMessageForCurrentEmployee(String messageId) {
        return findByMessage(messageId);
    }

    private ChatMessage requireOwnMessage(String messageId) {
        ChatMessage message = messageRepo.selectById(messageId);
        if (message == null || !cur.storeId().equals(message.getStoreId()) || !cur.employeeId().equals(message.getEmployeeId())) {
            throw BizException.notFound("AI 回答");
        }
        return message;
    }

    private ActionProposal requireOwnProposal(String proposalId) {
        ActionProposal proposal = findById(proposalId);
        if (proposal == null || !cur.storeId().equals(proposal.storeId()) || !cur.employeeId().equals(proposal.employeeId())) {
            throw BizException.notFound("AI 行动建议");
        }
        return proposal;
    }

    private ActionProposal findByMessage(String messageId) {
        var rows = jdbc.queryForList("""
            SELECT id, store_id, employee_id, message_id, customer_id, action_type, title, content,
                   assigned_to, priority, due_at, status, applied_task_id
            FROM ai_action_proposals
            WHERE message_id = ? AND store_id = ? AND employee_id = ? LIMIT 1
            """, messageId, cur.storeId(), cur.employeeId());
        return rows.isEmpty() ? null : toProposal(rows.get(0));
    }

    private ActionProposal findById(String id) {
        var rows = jdbc.queryForList("""
            SELECT id, store_id, employee_id, message_id, customer_id, action_type, title, content,
                   assigned_to, priority, due_at, status, applied_task_id
            FROM ai_action_proposals WHERE id = ? LIMIT 1
            """, id);
        return rows.isEmpty() ? null : toProposal(rows.get(0));
    }

    private ActionProposal toProposal(Map<String, Object> row) {
        return new ActionProposal(
            string(row.get("id")), string(row.get("store_id")), string(row.get("employee_id")),
            string(row.get("message_id")), string(row.get("customer_id")), string(row.get("action_type")),
            string(row.get("title")), string(row.get("content")), string(row.get("assigned_to")), string(row.get("priority")),
            row.get("due_at") == null ? null : String.valueOf(row.get("due_at")),
            string(row.get("status")), row.get("applied_task_id") == null ? null : String.valueOf(row.get("applied_task_id"))
        );
    }

    private String extractNextAction(String answer) {
        String text = answer == null ? "" : answer.trim();
        int marker = text.indexOf("下一步动作");
        if (marker >= 0) {
            String section = text.substring(marker + "下一步动作".length());
            int end = section.indexOf("是否需要升级");
            if (end >= 0) section = section.substring(0, end);
            section = section.replaceFirst("^[：:：\\s]*", "").trim();
            if (!section.isBlank()) return truncate(section, 1000);
        }
        return truncate(text.isBlank() ? "根据本次 AI 建议完成客户跟进" : text, 1000);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value; }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }

    public List<EmployeeOption> listAssignableEmployees() {
        if (cur.isAdmin()) {
            return jdbc.query("""
                SELECT id, name, role FROM employees
                WHERE store_id = ? AND status = 'active' ORDER BY name ASC
                """, (rs, rowNum) -> new EmployeeOption(rs.getString("id"), rs.getString("name"), rs.getString("role")), cur.storeId());
        }
        return jdbc.query("""
            SELECT id, name, role FROM employees
            WHERE id = ? AND store_id = ? AND status = 'active'
            """, (rs, rowNum) -> new EmployeeOption(rs.getString("id"), rs.getString("name"), rs.getString("role")),
            cur.employeeId(), cur.storeId());
    }

    private boolean canAssignTo(String employeeId) {
        if (!cur.isAdmin() && !cur.employeeId().equals(employeeId)) return false;
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM employees WHERE id = ? AND store_id = ? AND status = 'active'
            """, Integer.class, employeeId, cur.storeId());
        return count != null && count > 0;
    }

    private OffsetDateTime parseDueAt(String raw) {
        String value = normalize(raw);
        if (value == null) throw BizException.badRequest("请设置待办截止时间");
        try { return OffsetDateTime.parse(value); } catch (Exception ignored) { }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        } catch (Exception ignored) {
            throw BizException.badRequest("截止时间格式不正确");
        }
    }

    public record ActionProposal(
        String id, String storeId, String employeeId, String messageId, String customerId,
        String actionType, String title, String content, String assignedTo, String priority,
        String dueAt, String status, String appliedTaskId
    ) {}

    public record EmployeeOption(String id, String name, String role) {}
}
