package com.storeai.knowledge.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 会谈经验审核：店长审核通过后将优质话术沉淀为知识库文档。
 */
@Service
@RequiredArgsConstructor
public class ExperienceReviewService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    public Map<String, Object> approve(String taskId, String title, String category) {
        if (!cur.isAdmin()) throw BizException.forbidden();

        Map<String, Object> task = validateAndGet(taskId);
        String storeId = (String) task.get("store_id");
        String content = (String) task.get("content");

        String scene = extractScene(content);
        String docTitle = title != null && !title.isBlank() ? title : "会谈优质话术 · " + scene;
        String docCategory = category != null && !category.isBlank() ? category : "会谈沉淀";

        String docId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
            "INSERT INTO knowledge_documents (id, store_id, title, category, status, uploaded_by, visible_roles, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, 'active', ?, '[\"owner\",\"manager\",\"consultant\"]', ?, ?)",
            docId, storeId, docTitle, docCategory, cur.employeeId(),
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());

        String chunk = buildChunk(content);
        jdbc.update(
            "INSERT INTO knowledge_chunks (id, store_id, document_id, content, seq, created_at) VALUES (?, ?, ?, ?, 0, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId, docId, chunk,
            OffsetDateTime.now().toString());

        jdbc.update(
            "UPDATE tasks SET status = 'done', feedback = ?, updated_at = ? WHERE id = ?",
            "审核通过，已沉淀为知识库文档：" + docId, OffsetDateTime.now().toString(), taskId);

        return Map.of("document_id", docId, "status", "approved");
    }

    public Map<String, Object> reject(String taskId, String reason) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        validateAndGet(taskId);
        jdbc.update(
            "UPDATE tasks SET status = 'done', feedback = ?, updated_at = ? WHERE id = ?",
            "审核未通过：" + reason, OffsetDateTime.now().toString(), taskId);
        return Map.of("status", "rejected");
    }

    private Map<String, Object> validateAndGet(String taskId) {
        Map<String, Object> task;
        try {
            task = jdbc.queryForMap("SELECT * FROM tasks WHERE id = ? AND store_id = ?", taskId, cur.storeId());
        } catch (Exception e) {
            throw BizException.notFound("审核任务");
        }
        if (!"experience_review".equals(task.get("type"))) {
            throw BizException.badRequest("该任务不是经验审核任务");
        }
        return task;
    }

    private String extractScene(String content) {
        if (content == null) return "未命名";
        int idx = content.indexOf("场景：");
        if (idx < 0) return "未命名";
        int end = content.indexOf("\n", idx + 3);
        return end < 0 ? content.substring(idx + 3).trim() : content.substring(idx + 3, end).trim();
    }

    private String buildChunk(String content) {
        int start = content.indexOf("【建议话术】");
        if (start < 0) start = 0;
        int end = content.indexOf("\n\n请审核：");
        if (end < 0) end = content.length();
        return content.substring(start, end).trim();
    }
}
