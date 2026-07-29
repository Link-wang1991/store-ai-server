package com.storeai.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务的唯一追溯出口。
 *
 * 任务页、首页和客户闭环不能各自猜测“这条待办从哪里来”。这里把来源、客户、
 * 会谈、AI 对话及当时实际命中的知识快照统一组装；对历史旧记录宁可标记“未记录”，
 * 也不倒推或伪造知识引用。
 */
@Service
@RequiredArgsConstructor
public class TaskTraceService {

    private static final int MAX_SOURCE_DEPTH = 4;

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final ObjectMapper mapper;

    /** 当前登录员工的全部任务，和旧 /api/tasks 的权限范围保持一致。 */
    public List<Map<String, Object>> listForCurrentEmployee(String status) {
        StringBuilder sql = new StringBuilder("""
            SELECT t.id, t.store_id, t.customer_id, t.title, t.content, t.type, t.status, t.priority,
                   t.assigned_to, t.created_by, t.due_at, t.feedback, t.source_type, t.source_id,
                   t.source_meeting_id, t.created_at, t.updated_at,
                   c.name AS customer_name, c.stage AS customer_stage
            FROM tasks t
            LEFT JOIN customers c ON c.id = t.customer_id AND c.store_id = t.store_id
            WHERE t.store_id = ? AND t.assigned_to = ?
            """);
        List<Object> params = new ArrayList<>();
        params.add(cur.storeId());
        params.add(cur.employeeId());
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            params.add(status.trim());
        }
        sql.append(" ORDER BY t.created_at DESC");
        return enrich(jdbc.queryForList(sql.toString(), params.toArray()));
    }

    /** 首页只读正式未完成待办，且优先级和截止时间的排序口径固定在后端。 */
    public List<Map<String, Object>> listOpenForCurrentEmployee() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT t.id, t.store_id, t.customer_id, t.title, t.content, t.type, t.status, t.priority,
                   t.assigned_to, t.created_by, t.due_at, t.feedback, t.source_type, t.source_id,
                   t.source_meeting_id, t.created_at, t.updated_at,
                   c.name AS customer_name, c.stage AS customer_stage
            FROM tasks t
            LEFT JOIN customers c ON c.id = t.customer_id AND c.store_id = t.store_id
            WHERE t.store_id = ? AND t.assigned_to = ?
              AND t.status IN ('todo', 'doing', 'overdue')
            ORDER BY CASE t.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 ELSE 2 END,
                     CASE WHEN t.due_at IS NULL THEN 1 ELSE 0 END,
                     t.due_at ASC, t.created_at DESC
            """, cur.storeId(), cur.employeeId());
        return enrich(rows);
    }

    private List<Map<String, Object>> enrich(List<Map<String, Object>> rows) {
        Map<String, String> chunkDocumentCache = new LinkedHashMap<>();
        Map<String, CoachSource> coachCache = new LinkedHashMap<>();
        Map<String, MeetingSource> meetingCache = new LinkedHashMap<>();
        Map<String, SourceRoot> rootCache = new LinkedHashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> task = new LinkedHashMap<>(row);
            attachCustomer(task);
            attachTrace(task, chunkDocumentCache, coachCache, meetingCache, rootCache);
            result.add(task);
        }
        return result;
    }

    private void attachCustomer(Map<String, Object> task) {
        String customerId = text(task.get("customer_id"));
        if (customerId.isBlank()) return;
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", customerId);
        customer.put("name", task.get("customer_name"));
        customer.put("stage", task.get("customer_stage"));
        task.put("customer", customer);
    }

    private void attachTrace(Map<String, Object> task,
                             Map<String, String> chunkDocumentCache,
                             Map<String, CoachSource> coachCache,
                             Map<String, MeetingSource> meetingCache,
                             Map<String, SourceRoot> rootCache) {
        String directType = text(task.get("source_type"));
        String directId = text(task.get("source_id"));
        String directMeetingId = text(task.get("source_meeting_id"));
        task.put("source_label", sourceLabel(directType));

        SourceRoot root = resolveRoot(directType, directId, directMeetingId, rootCache, new HashSet<>(), 0);
        if (!root.type().isBlank() && !root.type().equals(directType)) {
            task.put("origin_source_type", root.type());
            task.put("origin_source_label", sourceLabel(root.type()));
            task.put("origin_source_id", root.id());
        }
        if (!root.meetingId().isBlank() && directMeetingId.isBlank()) task.put("source_meeting_id", root.meetingId());
        List<String> chain = new ArrayList<>();
        if (!directType.isBlank()) chain.add(sourceLabel(directType));
        if (!root.type().isBlank() && !root.type().equals(directType)) chain.add(sourceLabel(root.type()));
        task.put("source_chain", chain);

        if ("ai_coach".equals(root.type()) && !root.id().isBlank()) {
            CoachSource source = coachCache.computeIfAbsent(root.id(), this::loadCoachSource);
            if (source != null) {
                if (!source.sessionId().isBlank()) task.put("source_chat_session_id", source.sessionId());
                if (!source.messageId().isBlank()) task.put("source_message_id", source.messageId());
                if (!source.question().isBlank()) task.put("source_summary", source.question());
                task.put("knowledge_evidence", parseKnowledgeEvidence(source.retrievedChunks(), chunkDocumentCache));
                task.put("methodology_evidence", parseMethodologyEvidence(source.methodologySources()));
                task.put("source_status", sourceStatus(source.appliedTaskStatus(), source.appliedTaskFeedback()));
            }
        } else if (isMeetingSource(root.type()) && (!root.id().isBlank() || !root.meetingId().isBlank())) {
            String key = !root.id().isBlank() ? root.id() : "meeting:" + root.meetingId();
            MeetingSource source = meetingCache.computeIfAbsent(key, ignored -> loadMeetingSource(root));
            if (source != null) {
                if (!source.summary().isBlank()) task.put("source_summary", source.summary());
                task.put("knowledge_evidence", parseKnowledgeEvidence(source.knowledgeSources(), chunkDocumentCache));
                task.put("methodology_evidence", parseMethodologyEvidence(source.methodologySources()));
                if (!source.closureStatus().isBlank()) task.put("source_status", meetingClosureLabel(source.closureStatus()));
            }
        }

        task.putIfAbsent("knowledge_evidence", List.of());
        task.putIfAbsent("methodology_evidence", List.of());
    }

    /** task_feedback 指向上一条任务，因此递归到最初的会谈/AI 教练来源。 */
    private SourceRoot resolveRoot(String type, String id, String meetingId,
                                   Map<String, SourceRoot> cache, Set<String> seen, int depth) {
        String safeType = type == null ? "" : type.trim();
        String safeId = id == null ? "" : id.trim();
        String safeMeetingId = meetingId == null ? "" : meetingId.trim();
        if (!"task_feedback".equals(safeType) || safeId.isBlank() || depth >= MAX_SOURCE_DEPTH || !seen.add(safeId)) {
            return new SourceRoot(safeType, safeId, safeMeetingId);
        }
        SourceRoot cached = cache.get(safeId);
        if (cached != null) return cached;
        try {
            List<Map<String, Object>> parents = jdbc.queryForList("""
                SELECT source_type, source_id, source_meeting_id
                FROM tasks WHERE id = ? AND store_id = ? LIMIT 1
                """, safeId, cur.storeId());
            if (parents.isEmpty()) return new SourceRoot(safeType, safeId, safeMeetingId);
            Map<String, Object> parent = parents.get(0);
            SourceRoot root = resolveRoot(text(parent.get("source_type")), text(parent.get("source_id")),
                firstNonBlank(text(parent.get("source_meeting_id")), safeMeetingId), cache, seen, depth + 1);
            cache.put(safeId, root);
            return root;
        } catch (Exception ignored) {
            return new SourceRoot(safeType, safeId, safeMeetingId);
        }
    }

    private CoachSource loadCoachSource(String proposalId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ap.id AS proposal_id, ap.message_id, cm.session_id, cm.content AS question,
                       cm.retrieved_chunks, cm.methodology_sources,
                       created_task.status AS applied_task_status, created_task.feedback AS applied_task_feedback
                FROM ai_action_proposals ap
                JOIN chat_messages cm ON cm.id = ap.message_id AND cm.store_id = ap.store_id
                LEFT JOIN tasks created_task ON created_task.id = ap.applied_task_id AND created_task.store_id = ap.store_id
                WHERE ap.id = ? AND ap.store_id = ? LIMIT 1
                """, proposalId, cur.storeId());
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            return new CoachSource(text(row.get("message_id")), text(row.get("session_id")), text(row.get("question")),
                text(row.get("retrieved_chunks")), text(row.get("methodology_sources")),
                text(row.get("applied_task_status")), text(row.get("applied_task_feedback")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private MeetingSource loadMeetingSource(SourceRoot root) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ma.report, ma.summary, m.closure_status
                FROM meeting_analysis ma
                JOIN meetings m ON m.id = ma.meeting_id AND m.store_id = ma.store_id
                WHERE ma.store_id = ?
                  AND (ma.id = ? OR (? <> '' AND ma.meeting_id = ?))
                ORDER BY ma.updated_at DESC, ma.created_at DESC
                LIMIT 1
                """, cur.storeId(), root.id(), root.meetingId(), root.meetingId());
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            JsonNode report = readJson(text(row.get("report")));
            return new MeetingSource(text(row.get("summary")),
                report == null ? "" : jsonText(report.get("knowledge_sources")),
                report == null ? "" : jsonText(report.get("methodology_sources")),
                text(row.get("closure_status")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> parseKnowledgeEvidence(String raw, Map<String, String> chunkDocumentCache) {
        JsonNode nodes = readJson(raw);
        if (nodes == null || !nodes.isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            String chunkId = firstNonBlank(jsonValue(node, "chunk_id"), jsonValue(node, "chunkId"));
            String documentId = firstNonBlank(jsonValue(node, "document_id"), jsonValue(node, "documentId"));
            if (documentId.isBlank() && !chunkId.isBlank()) documentId = chunkDocumentCache.computeIfAbsent(chunkId, this::documentIdForChunk);
            String title = firstNonBlank(jsonValue(node, "title"), jsonValue(node, "documentTitle"));
            String excerpt = firstNonBlank(jsonValue(node, "excerpt"), jsonValue(node, "snippet"), jsonValue(node, "content"));
            String dedupe = documentId + "|" + chunkId + "|" + title;
            if ((documentId.isBlank() && title.isBlank()) || !seen.add(dedupe)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("document_id", documentId);
            item.put("chunk_id", chunkId);
            item.put("title", title.isBlank() ? "门店知识" : title);
            item.put("excerpt", clip(excerpt, 360));
            result.add(item);
            if (result.size() >= 3) break;
        }
        return result;
    }

    private List<Map<String, Object>> parseMethodologyEvidence(String raw) {
        JsonNode nodes = readJson(raw);
        if (nodes == null || !nodes.isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            String id = jsonValue(node, "id");
            String title = jsonValue(node, "title");
            if ((id.isBlank() && title.isBlank()) || !seen.add(id + "|" + title)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("title", title.isBlank() ? "系统销售方法论" : title);
            item.put("module", firstNonBlank(jsonValue(node, "module"), jsonValue(node, "category")));
            item.put("source", jsonValue(node, "source"));
            item.put("excerpt", firstNonBlank(jsonValue(node, "excerpt"), jsonValue(node, "content")));
            result.add(item);
            if (result.size() >= 3) break;
        }
        return result;
    }

    private String documentIdForChunk(String chunkId) {
        try {
            List<String> rows = jdbc.queryForList("SELECT document_id FROM knowledge_chunks WHERE id = ? AND store_id = ? LIMIT 1",
                String.class, chunkId, cur.storeId());
            return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
        } catch (Exception ignored) {
            return "";
        }
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return mapper.readTree(raw); } catch (Exception ignored) { return null; }
    }

    private String jsonText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        try { return mapper.writeValueAsString(node); } catch (Exception ignored) { return ""; }
    }

    private String jsonValue(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private boolean isMeetingSource(String sourceType) {
        return "meeting_analysis".equals(sourceType) || "meeting_reanalysis".equals(sourceType)
            || "followup_review".equals(sourceType) || "experience_review".equals(sourceType)
            || "memory_confirm".equals(sourceType) || "manual_meeting_candidate".equals(sourceType);
    }

    private String sourceLabel(String sourceType) {
        return switch (sourceType == null ? "" : sourceType) {
            case "meeting_analysis" -> "会谈分析";
            case "meeting_reanalysis" -> "会谈修订复盘";
            case "ai_coach" -> "AI 教练建议";
            case "task_feedback" -> "上次任务反馈";
            case "experience_review" -> "会谈经验审核";
            case "memory_confirm" -> "客户记忆确认";
            case "manual_meeting_candidate" -> "人工提交的会谈经验";
            case "followup_review" -> "会谈行动复核";
            case "compliance_fix" -> "会谈合规整改";
            case "score_coaching" -> "会谈质量辅导";
            default -> sourceType == null || sourceType.isBlank() ? "人工创建" : sourceType;
        };
    }

    private String sourceStatus(String status, String feedback) {
        if ("done".equals(status)) return feedback == null || feedback.isBlank() ? "AI 建议已完成" : "AI 建议已完成：" + feedback;
        if ("doing".equals(status)) return "AI 建议待办处理中";
        if ("todo".equals(status) || "overdue".equals(status)) return "AI 建议待办待执行";
        return "";
    }

    private String meetingClosureLabel(String status) {
        return switch (status) {
            case "completed" -> "会谈业务动作已闭环";
            case "partial_failed" -> "会谈业务动作待重试";
            case "processing", "pending" -> "会谈业务动作处理中";
            default -> "";
        };
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }
    private String clip(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private record SourceRoot(String type, String id, String meetingId) {}
    private record CoachSource(String messageId, String sessionId, String question, String retrievedChunks,
                               String methodologySources, String appliedTaskStatus, String appliedTaskFeedback) {}
    private record MeetingSource(String summary, String knowledgeSources, String methodologySources, String closureStatus) {}
}
