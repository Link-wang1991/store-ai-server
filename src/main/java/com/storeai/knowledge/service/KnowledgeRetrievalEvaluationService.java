package com.storeai.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检索质检题库。管理员录入真实业务问题与期望命中的资料，系统保存本次真实结果；
 * 不能拿“模型自评”或未人工标注的数据来假装检索准确率。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalEvaluationService {
    private final JdbcTemplate jdbc;
    private final KnowledgeService knowledgeService;
    private final CurrentUser cur;
    private final ObjectMapper objectMapper;

    public Map<String, Object> run(String question, String expectedDocumentId) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        String q = question == null ? "" : question.trim();
        if (q.length() < 2) throw BizException.badRequest("请填写至少两个字的真实业务问题");
        String expected = expectedDocumentId == null ? "" : expectedDocumentId.trim();
        if (!expected.isBlank()) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE id = ? AND store_id = ?", Integer.class, expected, cur.storeId());
            if (count == null || count == 0) throw BizException.badRequest("期望资料不属于当前门店");
        }
        List<KnowledgeRetrieveService.RetrievedChunk> hits = knowledgeService.search(q, 5);
        List<String> returned = new ArrayList<>(new LinkedHashSet<>(hits.stream().map(KnowledgeRetrieveService.RetrievedChunk::documentId).filter(id -> id != null && !id.isBlank()).toList()));
        String status = expected.isBlank() ? "unrated" : returned.contains(expected) ? "pass" : "fail";
        String id = UUID.randomUUID().toString().replace("-", "");
        String returnedJson;
        try { returnedJson = objectMapper.writeValueAsString(returned); }
        catch (Exception e) { throw new BizException("无法保存检索结果"); }
        Double topScore = hits.isEmpty() ? null : hits.get(0).score();
        jdbc.update("""
            INSERT INTO knowledge_retrieval_evaluations
            (id, store_id, question, expected_document_id, returned_document_ids, top_score, evaluation_status, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
            """, id, cur.storeId(), q, expected.isBlank() ? null : expected, returnedJson, topScore, status, cur.employeeId());
        return item(id, q, expected.isBlank() ? null : expected, returned, topScore, status, null);
    }

    public Map<String, Object> review(String id, String status, String note) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        String verdict = status == null ? "" : status.trim().toLowerCase();
        if (!List.of("pass", "fail", "unrated").contains(verdict)) throw BizException.badRequest("质检结论仅支持 pass、fail、unrated");
        String text = note == null ? null : note.trim();
        if (text != null && text.length() > 1500) throw BizException.badRequest("质检说明不能超过 1500 字");
        int updated = jdbc.update("""
            UPDATE knowledge_retrieval_evaluations
            SET evaluation_status = ?, note = ?, reviewed_by = ?, reviewed_at = NOW()
            WHERE id = ? AND store_id = ?
            """, verdict, text == null || text.isBlank() ? null : text, cur.employeeId(), id, cur.storeId());
        if (updated != 1) throw BizException.notFound("检索质检记录");
        return Map.of("id", id, "evaluation_status", verdict, "message", "质检结论已保存");
    }

    public Map<String, Object> list() {
        if (!cur.isAdmin()) throw BizException.forbidden();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT e.*, expected.title AS expected_document_title
            FROM knowledge_retrieval_evaluations e
            LEFT JOIN knowledge_documents expected ON expected.id = e.expected_document_id AND expected.store_id = e.store_id
            WHERE e.store_id = ? ORDER BY e.created_at DESC LIMIT 100
            """, cur.storeId());
        int labelled = 0, passed = 0, failed = 0, zeroHit = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> returned = parseReturned(row.get("returned_document_ids"));
            String status = String.valueOf(row.get("evaluation_status"));
            if ("pass".equals(status) || "fail".equals(status)) labelled++;
            if ("pass".equals(status)) passed++;
            if ("fail".equals(status)) failed++;
            if (returned.isEmpty()) zeroHit++;
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("returned_document_ids", returned);
            items.add(item);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", rows.size());
        summary.put("labelled", labelled);
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("unrated", rows.size() - labelled);
        summary.put("hit_rate", labelled == 0 ? null : Math.round((passed * 1000D) / labelled) / 10D);
        summary.put("zero_hit_rate", rows.isEmpty() ? null : Math.round((zeroHit * 1000D) / rows.size()) / 10D);
        summary.put("method", "命中率=期望资料已选定且确实出现在本次前五条召回结果中的比例；未选择期望资料的记录只用于人工复查，不计入命中率。");
        return Map.of("summary", summary, "items", items);
    }

    private List<String> parseReturned(Object raw) {
        if (raw == null) return List.of();
        try { return objectMapper.readValue(String.valueOf(raw), new TypeReference<List<String>>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    private Map<String, Object> item(String id, String question, String expected, List<String> returned,
                                     Double topScore, String status, String note) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("question", question);
        result.put("expected_document_id", expected);
        result.put("returned_document_ids", returned);
        result.put("top_score", topScore);
        result.put("evaluation_status", status);
        result.put("note", note);
        result.put("created_at", OffsetDateTime.now());
        return result;
    }
}
