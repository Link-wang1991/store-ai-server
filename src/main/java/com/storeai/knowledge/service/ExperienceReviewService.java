package com.storeai.knowledge.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会谈经验审核。
 *
 * 会谈分析只能形成「候选」；只有店长/老板在这里编辑并批准后，内容才会成为
 * 正式 knowledge_documents / knowledge_chunks。所有审核结果均保留来源会谈和审核人。
 */
@Service
@RequiredArgsConstructor
public class ExperienceReviewService {

    private static final List<String> DEFAULT_VISIBLE_ROLES = List.of(
        "owner", "manager", "consultant", "beautician", "receptionist", "operator");

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    /** 仅管理者可查看待审核候选。 */
    public List<ReviewItem> listPending() {
        requireAdmin();
        List<ReviewItem> rows = jdbc.query("""
            SELECT t.id, t.title, t.content, t.status, t.source_type, t.source_id,
                   t.source_meeting_id, t.created_at, e.name AS submitted_by_name
            FROM tasks t
            LEFT JOIN employees e ON e.id = t.created_by
            WHERE t.store_id = ?
              AND t.type = 'experience_review'
              AND t.status IN ('todo', 'doing')
            ORDER BY t.created_at ASC
            """, (rs, index) -> new ReviewItem(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getString("source_type"),
                rs.getString("source_id"),
                sourceMeetingId(rs.getString("source_meeting_id"), rs.getString("content")),
                rs.getString("submitted_by_name"),
                rs.getString("created_at")
            ), cur.storeId());
        return rows;
    }

    /**
     * 员工可把某一段会谈复盘提交为候选。它只创建审核任务，绝不直接发布。
     */
    @Transactional
    public Map<String, Object> submit(String meetingId, String title, String content, String category) {
        if (meetingId == null || meetingId.isBlank()) throw BizException.badRequest("缺少来源会谈");
        String cleanTitle = clean(title, 160);
        String cleanContent = clean(content, 12_000);
        if (cleanTitle.isBlank()) throw BizException.badRequest("请填写经验标题");
        if (cleanContent.isBlank()) throw BizException.badRequest("请填写待审核内容");

        Map<String, Object> meeting;
        try {
            meeting = jdbc.queryForMap(
                "SELECT id, employee_id, scene, customer_name FROM meetings WHERE id = ? AND store_id = ?",
                meetingId, cur.storeId());
        } catch (Exception e) {
            throw BizException.notFound("会谈");
        }
        if (!cur.isAdmin() && !cur.employeeId().equals(meeting.get("employee_id"))) {
            throw BizException.forbidden("无权提交该会谈的经验");
        }

        String taskTitle = "审核会谈经验：" + cleanTitle;
        Integer duplicate = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND type = 'experience_review' AND source_meeting_id = ?
              AND title = ? AND status IN ('todo', 'doing')
            """, Integer.class, cur.storeId(), meetingId, taskTitle);
        if (duplicate != null && duplicate > 0) {
            return Map.of("status", "pending", "message", "该经验已提交审核，请勿重复提交");
        }

        String analysisId = latestAnalysisId(meetingId);
        String managerId = findManagerId(cur.storeId());
        if (managerId == null) throw BizException.badRequest("当前门店没有可审核的老板或店长");

        String scene = String.valueOf(meeting.getOrDefault("scene", "未命名场景"));
        String customerName = String.valueOf(meeting.getOrDefault("customer_name", ""));
        String taskContent = "会谈 ID：" + meetingId + "\n场景：" + scene
            + (customerName.isBlank() ? "" : "\n客户：" + customerName)
            + "\n建议分类：" + (clean(category, 60).isBlank() ? "会谈沉淀" : clean(category, 60))
            + "\n\n【待审核内容】\n" + cleanContent
            + "\n\n请审核：核对原录音/逐字稿，完成必要脱敏和改写后再发布为全店知识。";

        String taskId = insertReviewTask(taskTitle, taskContent, managerId, cur.employeeId(),
            "manual_meeting_candidate", analysisId, meetingId);
        return Map.of("status", "pending", "task_id", taskId, "message", "已提交给店长/老板审核");
    }

    /** 审核通过：以审核人编辑后的内容创建正式知识文档。 */
    @Transactional
    public Map<String, Object> approve(String taskId, String title, String category,
                                       String content, List<String> visibleRoles) {
        requireAdmin();
        Map<String, Object> task = validateAndGetOpen(taskId);

        String reviewedContent = clean(content, 12_000);
        if (reviewedContent.isBlank()) reviewedContent = extractCandidateContent((String) task.get("content"));
        if (reviewedContent.isBlank()) throw BizException.badRequest("审核内容不能为空");

        String docTitle = clean(title, 160);
        if (docTitle.isBlank()) docTitle = defaultDocumentTitle((String) task.get("title"));
        String docCategory = clean(category, 60);
        if (docCategory.isBlank()) docCategory = categoryFromTask((String) task.get("content"));
        if (docCategory.isBlank()) docCategory = "会谈沉淀";

        String docId = UUID.randomUUID().toString().replace("-", "");
        String sourceType = (String) task.get("source_type");
        String sourceId = (String) task.get("source_id");
        String sourceMeetingId = sourceMeetingId((String) task.get("source_meeting_id"), (String) task.get("content"));
        String now = OffsetDateTime.now().toString();

        jdbc.update("""
            INSERT INTO knowledge_documents
                (id, store_id, title, category, status, uploaded_by, visible_roles, tags, remark,
                 file_type, source_type, source_id, source_meeting_id, reviewed_by, reviewed_at,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, 'active', ?, ?, '会谈沉淀', ?, 'meeting-review', ?, ?, ?, ?, ?, ?, ?)
            """,
            docId, cur.storeId(), docTitle, docCategory, cur.employeeId(),
            toJsonArray(normalizeRoles(visibleRoles)),
            sourceMeetingId == null ? "会谈经验审核通过" : "会谈经验审核通过，来源会谈：" + sourceMeetingId,
            sourceType, sourceId, sourceMeetingId, cur.employeeId(), now, now, now);

        jdbc.update("""
            INSERT INTO knowledge_chunks (id, store_id, document_id, content, seq, created_at)
            VALUES (?, ?, ?, ?, 0, ?)
            """, UUID.randomUUID().toString().replace("-", ""), cur.storeId(), docId, reviewedContent, now);

        jdbc.update("""
            UPDATE tasks
            SET status = 'done', feedback = ?, updated_at = ?
            WHERE id = ? AND store_id = ?
            """, "审核通过，已发布为正式知识库文档：" + docId, now, taskId, cur.storeId());

        return Map.of("document_id", docId, "status", "approved");
    }

    /** 审核驳回：保留任务和理由，不将内容写入知识库。 */
    @Transactional
    public Map<String, Object> reject(String taskId, String reason) {
        requireAdmin();
        validateAndGetOpen(taskId);
        String note = clean(reason, 1_000);
        if (note.isBlank()) note = "不符合当前门店知识沉淀要求";
        jdbc.update("""
            UPDATE tasks
            SET status = 'canceled', feedback = ?, updated_at = ?
            WHERE id = ? AND store_id = ?
            """, "审核驳回：" + note, OffsetDateTime.now().toString(), taskId, cur.storeId());
        return Map.of("status", "rejected");
    }

    /** 会谈分析自动形成的优质候选调用此方法；同样必须走审核。 */
    public String createAutomaticCandidate(String storeId, String meetingId, String analysisId, String scene,
                                           String customerName, String employeeId,
                                           String summary, String script, String didWell) {
        String managerId = findManagerId(storeId);
        if (managerId == null || employeeId == null || (script.isBlank() && didWell.isBlank())) return null;
        String content = "会谈 ID：" + meetingId + "\n场景：" + scene
            + (customerName == null || customerName.isBlank() ? "" : "\n客户：" + customerName)
            + "\n摘要：" + summary
            + (script.isBlank() ? "" : "\n\n【建议话术】\n" + script)
            + (didWell.isBlank() ? "" : "\n\n【值得复制的做法】\n" + didWell)
            + "\n\n请审核：核对原录音/逐字稿，完成必要脱敏和改写后再发布为全店知识。";
        return insertReviewTask(storeId, "审核会谈经验：" + scene, content, managerId, employeeId,
            "meeting_analysis", analysisId, meetingId);
    }

    private String insertReviewTask(String title, String content, String assignedTo, String createdBy,
                                    String sourceType, String sourceId, String sourceMeetingId) {
        return insertReviewTask(cur.storeId(), title, content, assignedTo, createdBy,
            sourceType, sourceId, sourceMeetingId);
    }

    private String insertReviewTask(String storeId, String title, String content, String assignedTo, String createdBy,
                                    String sourceType, String sourceId, String sourceMeetingId) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String now = OffsetDateTime.now().toString();
        jdbc.update("""
            INSERT INTO tasks
                (id, store_id, title, content, type, status, assigned_to, created_by,
                 source_type, source_id, source_meeting_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'experience_review', 'todo', ?, ?, ?, ?, ?, ?, ?)
            """, id, storeId, title, content, assignedTo, createdBy,
            sourceType, sourceId, sourceMeetingId, now, now);
        return id;
    }

    private Map<String, Object> validateAndGetOpen(String taskId) {
        Map<String, Object> task;
        try {
            task = jdbc.queryForMap("SELECT * FROM tasks WHERE id = ? AND store_id = ?", taskId, cur.storeId());
        } catch (Exception e) {
            throw BizException.notFound("审核任务");
        }
        if (!"experience_review".equals(task.get("type"))) throw BizException.badRequest("该任务不是经验审核任务");
        String status = String.valueOf(task.get("status"));
        if (!"todo".equals(status) && !"doing".equals(status)) throw BizException.badRequest("该审核任务已处理");
        return task;
    }

    private String latestAnalysisId(String meetingId) {
        try {
            return jdbc.queryForObject(
                "SELECT id FROM meeting_analysis WHERE meeting_id = ? AND store_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, meetingId, cur.storeId());
        } catch (Exception e) {
            return null;
        }
    }

    /** 查找门店负责人：owner → manager。 */
    private String findManagerId(String storeId) {
        List<String> ids = jdbc.queryForList("""
            SELECT id FROM employees
            WHERE store_id = ? AND role IN ('owner', 'manager') AND status = 'active'
            ORDER BY CASE role WHEN 'owner' THEN 0 ELSE 1 END
            LIMIT 1
            """, String.class, storeId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void requireAdmin() {
        if (!cur.isAdmin()) throw BizException.forbidden();
    }

    private String defaultDocumentTitle(String taskTitle) {
        if (taskTitle == null || taskTitle.isBlank()) return "会谈优质经验";
        return taskTitle.replaceFirst("^审核会谈经验：", "会谈经验 · ").trim();
    }

    private String extractCandidateContent(String taskContent) {
        if (taskContent == null || taskContent.isBlank()) return "";
        int start = taskContent.indexOf("【待审核内容】");
        if (start >= 0) start += "【待审核内容】".length();
        else {
            start = taskContent.indexOf("【建议话术】");
            if (start < 0) start = taskContent.indexOf("【值得复制的做法】");
            if (start < 0) start = 0;
        }
        int end = taskContent.indexOf("\n\n请审核：", start);
        return taskContent.substring(start, end < 0 ? taskContent.length() : end).trim();
    }

    private String categoryFromTask(String taskContent) {
        if (taskContent == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^建议分类：(.*)$").matcher(taskContent);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String sourceMeetingId(String explicit, String content) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (content == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^会谈 ID：(\\S+)$").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String clean(String value, int maxLength) {
        if (value == null) return "";
        String result = value.trim();
        return result.length() > maxLength ? result.substring(0, maxLength) : result;
    }

    private List<String> normalizeRoles(List<String> roles) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String role : roles == null || roles.isEmpty() ? DEFAULT_VISIBLE_ROLES : roles) {
            if (DEFAULT_VISIBLE_ROLES.contains(role)) result.add(role);
        }
        return result.isEmpty() ? DEFAULT_VISIBLE_ROLES : new ArrayList<>(result);
    }

    private String toJsonArray(List<String> values) {
        return "[" + values.stream().map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    public record ReviewItem(String id, String title, String content, String status,
                             String sourceType, String sourceId, String sourceMeetingId,
                             String submittedByName, String createdAt) {}
}
