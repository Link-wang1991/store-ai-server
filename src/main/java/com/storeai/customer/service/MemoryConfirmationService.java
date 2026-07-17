package com.storeai.customer.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 低可信度客户记忆的确认与修正。
 */
@Service
@RequiredArgsConstructor
public class MemoryConfirmationService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    private static final Pattern ANALYSIS_ID_PATTERN = Pattern.compile("来源会谈：([a-f0-9]+)");

    public Map<String, Object> confirmFromTask(String taskId, boolean confirmed, String correctedValue) {
        Map<String, Object> task = validateAndGet(taskId);
        String content = (String) task.get("content");

        String analysisId = extractAnalysisId(content);
        String key = extractKey(content);
        String customerId = extractCustomerId(content);

        if (analysisId == null || key == null) {
            throw BizException.badRequest("无法从任务内容解析记忆信息");
        }

        if (confirmed) {
            jdbc.update(
                "UPDATE memory_items SET confidence = 'high', updated_at = ? " +
                "WHERE source_type = 'meeting_analysis' AND source_id = ? AND `key` = ? AND store_id = ?",
                OffsetDateTime.now().toString(), analysisId, key, cur.storeId());
        }

        if (correctedValue != null && !correctedValue.isBlank()) {
            jdbc.update(
                "UPDATE memory_items SET value = ?, confidence = 'high', updated_at = ? " +
                "WHERE source_type = 'meeting_analysis' AND source_id = ? AND `key` = ? AND store_id = ?",
                correctedValue, OffsetDateTime.now().toString(), analysisId, key, cur.storeId());

            // 同时更新 customers 对应字段做汇总
            if (customerId != null) {
                updateCustomerField(customerId, key, correctedValue);
            }
        }

        jdbc.update(
            "UPDATE tasks SET status = 'done', feedback = ?, updated_at = ? WHERE id = ?",
            confirmed ? "已确认记忆准确" : "已修正记忆内容", OffsetDateTime.now().toString(), taskId);

        return Map.of("analysis_id", analysisId, "key", key, "confirmed", confirmed);
    }

    private Map<String, Object> validateAndGet(String taskId) {
        Map<String, Object> task;
        try {
            task = jdbc.queryForMap("SELECT * FROM tasks WHERE id = ? AND store_id = ?", taskId, cur.storeId());
        } catch (Exception e) {
            throw BizException.notFound("确认任务");
        }
        if (!"memory_confirm".equals(task.get("type"))) {
            throw BizException.badRequest("该任务不是记忆确认任务");
        }
        return task;
    }

    private String extractAnalysisId(String content) {
        Matcher m = ANALYSIS_ID_PATTERN.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private String extractKey(String content) {
        int idx = content.indexOf("类型：");
        if (idx < 0) return null;
        int end = content.indexOf("\n", idx + 3);
        return end < 0 ? content.substring(idx + 3).trim() : content.substring(idx + 3, end).trim();
    }

    private String extractCustomerId(String content) {
        // 当前任务内容里没有直接存 customerId，后续可优化
        return null;
    }

    private void updateCustomerField(String customerId, String key, String value) {
        String column = switch (key) {
            case "concerns" -> "concerns";
            case "needs" -> "ai_suggestion";
            case "emotional_needs" -> "ai_suggestion";
            default -> null;
        };
        if (column == null) return;
        jdbc.update(
            "UPDATE customers SET " + column + " = CONCAT(IFNULL(" + column + ",''), ?), updated_at = NOW() " +
            "WHERE id = ? AND store_id = ?",
            "\n【" + key + "修正】" + value, customerId, cur.storeId());
    }
}
