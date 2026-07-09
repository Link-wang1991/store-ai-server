package com.storeai.meeting.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.ai.AiAdapter;
import com.storeai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingAnalysisService {

    private final JdbcTemplate jdbc;
    private final AiAdapter aiAdapter;

    @Value("${ai.qwen.api-key:}")
    private String dashscopeKey;

    private static final String DS_BASE = "https://dashscope.aliyuncs.com/api/v1";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * 推进会谈状态机：transcribing → analyzing → done。
     * 幂等：重复调用不会重复插入。
     */
    public Map<String, Object> process(String meetingId) {
        var row = jdbc.queryForMap("SELECT * FROM meetings WHERE id = ?", meetingId);
        String status = (String) row.get("status");
        if ("done".equals(status) || "failed".equals(status)) {
            return Map.of("status", status);
        }

        try {
            if ("transcribing".equals(status)) {
                return handleTranscribing(row);
            }
            if ("analyzing".equals(status)) {
                return handleAnalyzing(row);
            }
        } catch (Exception e) {
            log.error("处理会谈失败: meeting={}, error={}", meetingId, e.getMessage());
            jdbc.update("UPDATE meetings SET status = 'failed' WHERE id = ?", meetingId);
            return Map.of("status", "failed", "error", e.getMessage());
        }
        return Map.of("status", status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleTranscribing(Map<String, Object> row) throws Exception {
        String id = (String) row.get("id");
        String asrTaskId = (String) row.get("asr_task_id");

        if (asrTaskId == null) {
            return Map.of("status", "transcribing");
        }

        // 轮询 DashScope
        var req = HttpRequest.newBuilder()
                .uri(URI.create(DS_BASE + "/tasks/" + asrTaskId))
                .header("Authorization", "Bearer " + dashscopeKey)
                .GET().build();
        var res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            log.warn("DashScope 查询失败: task={}, status={}", asrTaskId, res.statusCode());
            return Map.of("status", "transcribing");
        }

        Map<String, Object> data = jsonMapper.readValue(res.body(), Map.class);
        String taskStatus = (String) ((Map<String, Object>) data.get("output")).get("task_status");

        if ("PENDING".equals(taskStatus) || "RUNNING".equals(taskStatus)) {
            return Map.of("status", "transcribing");
        }

        if (!"SUCCEEDED".equals(taskStatus)) {
            // 转写失败
            String error = data.get("output") != null ? data.get("output").toString() : taskStatus;
            jdbc.update("UPDATE meetings SET status = 'failed', transcript_status = 'failed' WHERE id = ?", id);
            String reason = error.contains("NO_VALID_FRAGMENT")
                    ? "未识别到有效语音（录音可能太短、太嘈杂或无人说话）"
                    : "语音转写失败";
            return Map.of("status", "failed", "error", reason);
        }

        // 解析转写结果
        var results = (List<Map<String, Object>>) ((Map<String, Object>) data.get("output")).get("results");
        List<Map<String, Object>> segments = new ArrayList<>();

        for (var r : results) {
            String url = (String) r.get("transcription_url");
            if (url == null) continue;
            try {
                var trReq = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                var trRes = httpClient.send(trReq, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> trData = jsonMapper.readValue(trRes.body(), Map.class);
                var transcripts = (List<Map<String, Object>>) trData.get("transcripts");
                if (transcripts == null) continue;
                for (var t : transcripts) {
                    var sentences = (List<Map<String, Object>>) t.get("sentences");
                    if (sentences == null) continue;
                    for (var s : sentences) {
                        var seg = new HashMap<String, Object>();
                        seg.put("speaker", "speaker_" + s.getOrDefault("speaker_id", 0));
                        seg.put("start", ((Number) s.getOrDefault("begin_time", 0)).doubleValue() / 1000);
                        seg.put("end", ((Number) s.getOrDefault("end_time", 0)).doubleValue() / 1000);
                        seg.put("text", s.getOrDefault("text", ""));
                        segments.add(seg);
                    }
                }
            } catch (Exception e) {
                log.warn("跳过转写结果文件: {}", e.getMessage());
            }
        }

        if (segments.isEmpty()) {
            jdbc.update("UPDATE meetings SET status = 'failed', transcript_status = 'failed' WHERE id = ?", id);
            return Map.of("status", "failed", "error", "未识别到有效语音（录音可能太短、太嘈杂或无人说话）");
        }

        // 保存转写
        String storeId = (String) row.get("store_id");
        for (int i = 0; i < segments.size(); i++) {
            var seg = segments.get(i);
            jdbc.update(
                "INSERT INTO meeting_transcripts (id, meeting_id, store_id, speaker, content, start_time, end_time, seq, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString().replace("-", ""),
                id, storeId,
                seg.get("speaker"), seg.get("text"),
                seg.get("start"), seg.get("end"),
                i,
                OffsetDateTime.now().toString(), OffsetDateTime.now().toString()
            );
        }

        jdbc.update("UPDATE meetings SET transcript_status = 'done', status = 'analyzing' WHERE id = ?", id);
        return Map.of("status", "analyzing");
    }

    private Map<String, Object> handleAnalyzing(Map<String, Object> row) throws Exception {
        String id = (String) row.get("id");
        String storeId = (String) row.get("store_id");

        // 幂等：已有分析结果则直接 done
        var existing = jdbc.queryForList(
            "SELECT id FROM meeting_analysis WHERE meeting_id = ? AND store_id = ? LIMIT 1", id, storeId);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE meetings SET status = 'done', analysis_status = 'done' WHERE id = ?", id);
            return Map.of("status", "done");
        }

        // 读取转写
        var transcripts = jdbc.queryForList(
            "SELECT * FROM meeting_transcripts WHERE meeting_id = ? AND store_id = ? ORDER BY seq ASC", id, storeId);
        if (transcripts.isEmpty()) {
            throw new BizException("没有转写内容可分析");
        }

        // 构建分析 prompt
        String fullText = new ArrayList<String>() {{
            for (var t : transcripts) {
                add("[" + t.get("speaker") + "] " + t.get("content"));
            }
        }}.stream().reduce((a, b) -> a + "\n" + b).orElse("");

        String scene = (String) row.get("scene");

        String system = "你是一个门店销售咨询分析专家。根据以下会谈转写内容，输出 JSON 分析报告。";
        String user = String.format(
            "会谈场景：%s\n\n转写内容：\n%s\n\n请输出包含以下字段的 JSON：summary, explicit_needs, implicit_needs, emotional_needs, decision_barriers, employee_did_well, employee_to_improve, missed_opportunities, service_experience_risk, compliance_risks, followup_goal, suggested_followup_at, suggested_script, need_manager_involved",
            scene, fullText.length() > 30000 ? fullText.substring(0, 20000) + "\n…（中间略）…\n" + fullText.substring(fullText.length() - 10000) : fullText
        );

        String aiResult = aiAdapter.call(system, user, null);
        if (aiResult == null) {
            throw new BizException("AI 分析无返回");
        }

        // 提取 JSON
        String jsonStr = aiResult;
        int start = aiResult.indexOf('{');
        int end = aiResult.lastIndexOf('}');
        if (start >= 0 && end > start) {
            jsonStr = aiResult.substring(start, end + 1);
        }

        Map<String, Object> analysis;
        try {
            analysis = jsonMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("AI 返回非 JSON，原样保存: {}", aiResult);
            analysis = new HashMap<>();
            analysis.put("raw", aiResult);
        }

        // 保存分析结果
        String analysisId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
            "INSERT INTO meeting_analysis (id, meeting_id, store_id, report, summary, explicit_needs, implicit_needs, emotional_needs, decision_barriers, employee_did_well, employee_to_improve, missed_opportunities, compliance_risks, followup_goal, suggested_script, need_manager_involved, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            analysisId, id, storeId,
            jsonStr,
            safeStr(analysis.get("summary")),
            safeStr(analysis.get("explicit_needs")),
            safeStr(analysis.get("implicit_needs")),
            safeStr(analysis.get("emotional_needs")),
            safeStr(analysis.get("decision_barriers")),
            safeStr(analysis.get("employee_did_well")),
            safeStr(analysis.get("employee_to_improve")),
            safeStr(analysis.get("missed_opportunities")),
            safeStr(analysis.get("compliance_risks")),
            safeStr(analysis.get("followup_goal")),
            safeStr(analysis.get("suggested_script")),
            Boolean.TRUE.equals(analysis.getOrDefault("need_manager_involved", false)) ? 1 : 0,
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString()
        );

        jdbc.update("UPDATE meetings SET status = 'done', analysis_status = 'done' WHERE id = ?", id);
        return Map.of("status", "done");
    }

    /** 安全提取字符串：null→""，对象→JSON，字符串→原值 */
    private String safeStr(Object val) {
        if (val == null) return "";
        if (val instanceof String s) return s;
        try { return jsonMapper.writeValueAsString(val); }
        catch (Exception e) { return val.toString(); }
    }
}
