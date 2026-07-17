package com.storeai.knowledge.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识缺口审核闭环。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeGapService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
            "SELECT * FROM knowledge_gaps WHERE store_id = ? ORDER BY created_at DESC",
            cur.storeId());
    }

    public Map<String, Object> resolve(String id, String answer) {
        validateStore(id);
        jdbc.update(
            "UPDATE knowledge_gaps SET status = 'resolved', answer = ?, resolved_by = ?, resolved_at = ? WHERE id = ?",
            answer, cur.employeeId(), OffsetDateTime.now().toString(), id);
        closeRelatedReviewTask(id, "知识缺口已解决");
        return getById(id);
    }

    public Map<String, Object> toKnowledge(String id, String title, String content, String category) {
        validateStore(id);

        String docId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
            "INSERT INTO knowledge_documents (id, store_id, title, category, status, uploaded_by, visible_roles, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, 'active', ?, '[\"owner\",\"manager\",\"consultant\"]', ?, ?)",
            docId, cur.storeId(), title, category == null ? "script" : category, cur.employeeId(),
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());

        jdbc.update(
            "INSERT INTO knowledge_chunks (id, store_id, document_id, content, seq, created_at) VALUES (?, ?, ?, ?, 0, ?)",
            UUID.randomUUID().toString().replace("-", ""), cur.storeId(), docId, content,
            OffsetDateTime.now().toString());

        jdbc.update(
            "UPDATE knowledge_gaps SET status = 'converted', resolved_by = ?, resolved_at = ? WHERE id = ?",
            cur.employeeId(), OffsetDateTime.now().toString(), id);

        closeRelatedReviewTask(id, "已转化为知识库文档：" + docId);
        return getById(id);
    }

    private void closeRelatedReviewTask(String gapId, String feedback) {
        // 通过 content 中的知识缺口 ID 找到关联的 knowledge_review 任务并关闭
        jdbc.update(
            "UPDATE tasks SET status = 'done', feedback = ?, updated_at = ? " +
            "WHERE store_id = ? AND type = 'knowledge_review' AND content LIKE ? AND status != 'done'",
            feedback, OffsetDateTime.now().toString(), cur.storeId(), "%知识缺口 ID：" + gapId + "%");
    }

    private Map<String, Object> getById(String id) {
        return jdbc.queryForMap("SELECT * FROM knowledge_gaps WHERE id = ?", id);
    }

    private void validateStore(String id) {
        Integer cnt = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_gaps WHERE id = ? AND store_id = ?",
            Integer.class, id, cur.storeId());
        if (cnt == null || cnt == 0) {
            throw BizException.notFound("知识缺口");
        }
    }
}
