package com.storeai.meeting.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.ai.AiAdapter;
import com.storeai.common.exception.BizException;
import com.storeai.common.net.DirectProxySelector;
import com.storeai.customer.service.CustomerTimelineService;
import com.storeai.knowledge.service.ExperienceReviewService;
import com.storeai.knowledge.service.KnowledgeRetrieveService;
import com.storeai.knowledge.service.KnowledgeService;
import com.storeai.knowledge.service.SystemPlaybookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ComplianceScanner complianceScanner;
    private final CustomerTimelineService customerTimelineService;
    private final ExperienceReviewService experienceReviewService;
    private final KnowledgeService knowledgeService;
    private final SystemPlaybookService systemPlaybookService;
    private final MeetingQualityScorer meetingQualityScorer;

    @Value("${ai.qwen.api-key:}")
    private String dashscopeKey;

    /**
     * AI 会谈分析的最大尝试次数。默认 3 次；为临时关闭自动重试（避免反复调用模型消耗 token）
     * 可配置为 1，分析失败一次即进入用户可见的失败态，不再自动重试。
     */
    @Value("${meeting.analysis-max-attempts:3}")
    private int maxAnalysisAttempts;

    /** 单段文本超过该字符数则走分段分析（约 9000 中文字） */
    private static final int SINGLE_LIMIT = 9000;
    /** 每个分段的最大字符数（按 segment 边界切，不截断句子） */
    private static final int CHUNK_LIMIT = 6000;
    /** 会谈提示词只带最相关的少量资料，避免把整份知识库塞进模型造成噪声。 */
    private static final int MEETING_KNOWLEDGE_TOP_N = 3;
    private static final int MEETING_KNOWLEDGE_QUERY_LIMIT = 5_000;
    private static final int MEETING_KNOWLEDGE_CHUNK_LIMIT = 1_200;
    /** 这些字段落在 TEXT 列中，必须是干净的可读文本而不是 JSON 数组字符串。 */
    private static final Set<String> NARRATIVE_FIELDS = Set.of(
        "summary", "explicit_needs", "implicit_needs", "emotional_needs", "decision_barriers",
        "employee_did_well", "employee_to_improve", "missed_opportunities", "service_experience_risk",
        "compliance_risks", "followup_goal", "suggested_script", "customer_decision_stage",
        "judgement_basis", "professional_assessment", "next_step_plan", "knowledge_basis", "methodology_basis",
        "need_digging_evidence", "deal_advancing_evidence", "compliance_evidence", "service_evidence"
    );

    private static final String DS_BASE = "https://dashscope.aliyuncs.com/api/v1";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .proxy(DirectProxySelector.INSTANCE)
            .build();
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
            if ("queued".equals(status) || "submitting".equals(status)) {
                return currentStatus(meetingId);
            }
            if ("transcribing".equals(status)) {
                return handleTranscribing(row);
            }
            if ("analyzing".equals(status)) {
                return handleAnalyzing(row);
            }
        } catch (Exception e) {
            log.error("处理会谈失败: meeting={}, error={}", meetingId, e.getMessage());
            // 模型/网络异常不是“录音坏了”。保留已完成的逐句转写，按受控节奏重试，
            // 到达上限后才进入用户可见的失败状态，绝不写入空报告或伪造完成状态。
            if ("analyzing".equals(status)) {
                return scheduleAnalysisRetry(meetingId, "analysis_exception",
                    "AI 分析暂时不可用，系统会保留逐字稿并自动重试。", e);
            }
            String reason = userFacingProcessError(status, e);
            jdbc.update("""
                UPDATE meetings
                SET status = 'failed', transcript_status = CASE WHEN ? = 'transcribing' THEN 'failed' ELSE transcript_status END,
                    analysis_status = CASE WHEN ? = 'analyzing' THEN 'failed' ELSE analysis_status END,
                    fail_reason = ?, updated_at = NOW()
                WHERE id = ? AND status NOT IN ('done', 'failed')
                """, status, status, reason, meetingId);
            return Map.of("status", "failed", "error", reason);
        }
        return Map.of("status", status);
    }

    /**
     * 转写/分析不可依赖用户一直停留在报告页。后台每隔几秒推进一次，
     * 浏览器轮询只负责更快展示进度，而不是流程能否完成的前提。
     */
    @Scheduled(fixedDelayString = "${meeting.processing-poll-interval-ms:12000}")
    public void advancePendingMeetings() {
        // 进程若恰好在下载转写结果时重启，释放已领取但长期未完成的收尾锁，
        // 让下一个轮询安全重试，而不是永久停在 transcribing。
        jdbc.update("""
            UPDATE meetings
            SET transcript_status = 'transcribing', fail_reason = '正在恢复转写结果处理，请稍候。', updated_at = NOW()
            WHERE status = 'transcribing' AND transcript_status = 'finalizing'
              AND updated_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)
            """);
        List<String> ids = jdbc.queryForList("""
            SELECT id FROM meetings
            WHERE status IN ('transcribing', 'analyzing')
              AND (status <> 'analyzing' OR analysis_retry_at IS NULL OR analysis_retry_at <= NOW())
            ORDER BY updated_at ASC
            LIMIT 20
            """, String.class);
        for (String id : ids) {
            try {
                process(id);
            } catch (Exception e) {
                log.warn("后台推进会谈失败: meeting={}, reason={}", id, e.getMessage());
            }
        }
    }

    /**
     * 分析报告已经生成、但任务/记忆/审核等闭环动作出现局部失败时，后台会有限次补偿。
     * 历史会谈在 V15 迁移时已标为 completed，不会被这段逻辑批量重跑。
     */
    @Scheduled(fixedDelayString = "${meeting.closure-retry-interval-ms:60000}")
    public void recoverIncompleteClosures() {
        List<String> ids = jdbc.queryForList("""
            SELECT id FROM meetings
            WHERE status = 'done' AND closure_status IN ('pending', 'partial_failed')
              AND COALESCE(closure_attempts, 0) < 3
            ORDER BY updated_at ASC LIMIT 20
            """, String.class);
        for (String id : ids) {
            try {
                retryClosure(id);
            } catch (Exception e) {
                log.warn("重试会谈闭环失败: meeting={}, reason={}", id, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleTranscribing(Map<String, Object> row) throws Exception {
        String id = (String) row.get("id");
        String asrTaskId = (String) row.get("asr_task_id");

        if (asrTaskId == null) {
            jdbc.update("UPDATE meetings SET status = 'queued', transcript_status = 'pending', asr_retry_at = NOW(), updated_at = NOW() WHERE id = ?", id);
            return currentStatus(id);
        }
        if (dashscopeKey == null || dashscopeKey.isBlank()) {
            return failTranscription(id, "语音识别服务尚未配置，请联系管理员后重新提交。");
        }

        // 轮询 DashScope
        Map<String, Object> data;
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(DS_BASE + "/tasks/" + asrTaskId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + dashscopeKey)
                    .GET().build();
            var res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("DashScope 查询失败: task={}, status={}", asrTaskId, res.statusCode());
                return recordTranscriptionPollFailure(id, "语音识别服务暂时无法查询，正在重试。", "poll_unavailable");
            }
            data = jsonMapper.readValue(res.body(), Map.class);
        } catch (Exception e) {
            log.warn("DashScope 查询异常: task={}, reason={}", asrTaskId, e.getMessage());
            return recordTranscriptionPollFailure(id, "语音识别服务连接暂时中断，正在重试。", "poll_network");
        }

        Map<String, Object> output = asObjectMap(data.get("output"));
        if (output.isEmpty()) {
            return recordTranscriptionPollFailure(id);
        }
        String taskStatus = String.valueOf(output.get("task_status"));

        if ("PENDING".equals(taskStatus) || "RUNNING".equals(taskStatus)) {
            jdbc.update("UPDATE meetings SET asr_poll_failures = 0, asr_last_polled_at = NOW(), asr_error_code = NULL, fail_reason = NULL WHERE id = ?", id);
            return currentStatus(id);
        }

        if (!"SUCCEEDED".equals(taskStatus)) {
            // 转写失败
            String error = data.get("output") != null ? data.get("output").toString() : taskStatus;
            String reason;
            if (error.contains("NO_VALID_FRAGMENT")) {
                reason = "未识别到有效语音。可能原因：①录音太短（建议10秒以上）②没人说话或音量太小③环境太嘈杂。建议重录时靠近麦克风、保持环境安静。";
            } else if (error.contains("AudioDurationExceed")) {
                reason = "录音文件超长，语音转写仅支持最长6小时的音频。";
            } else if (error.contains("InvalidFile") || error.contains("Unsupported")) {
                reason = "录音格式不支持，请使用常见的音频格式（MP4/AAC/WebM/MP3）。";
            } else if (error.contains("file_url") || error.contains("download")) {
                reason = "语音识别服务无法获取录音文件，可能是网络问题，请重试。";
            } else {
                reason = "语音转写失败（" + taskStatus + "），请重新提交转写。";
            }
            return failTranscription(id, reason);
        }

        // 前端轮询和后台调度可能同时看到 SUCCEEDED。只有成功领取收尾权的一方
        // 才会写入逐句转写，防止重复记录或重复分析。
        int claimed = jdbc.update("""
            UPDATE meetings
            SET transcript_status = 'finalizing', asr_last_polled_at = NOW(), updated_at = NOW()
            WHERE id = ? AND status = 'transcribing'
              AND (transcript_status = 'transcribing' OR transcript_status IS NULL)
            """, id);
        if (claimed != 1) {
            return currentStatus(id);
        }

        // 解析转写结果
        List<Map<String, Object>> results = asMapList(output.get("results"));
        if (results.isEmpty()) {
            return recordTranscriptionResultFailure(id, "语音识别已完成但暂未返回结果文件，正在恢复。", "result_missing");
        }
        List<Map<String, Object>> segments = new ArrayList<>();
        boolean anyResultDownloaded = false;

        for (var r : results) {
            String url = (String) r.get("transcription_url");
            if (url == null) continue;
            try {
                var trReq = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(45)).GET().build();
                var trRes = httpClient.send(trReq, HttpResponse.BodyHandlers.ofString());
                if (trRes.statusCode() != 200) {
                    log.warn("转写结果下载失败: status={}", trRes.statusCode());
                    continue;
                }
                Map<String, Object> trData = jsonMapper.readValue(trRes.body(), Map.class);
                anyResultDownloaded = true;
                var transcripts = asMapList(trData.get("transcripts"));
                for (var t : transcripts) {
                    var sentences = asMapList(t.get("sentences"));
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
            if (!anyResultDownloaded) {
                return recordTranscriptionResultFailure(id, "转写结果暂时无法下载，正在自动重试。", "result_download");
            }
            return failTranscription(id, "未识别到有效语音（录音可能太短、太嘈杂或无人说话）");
        }

        // 保存转写
        String storeId = (String) row.get("store_id");
        jdbc.update("DELETE FROM meeting_transcripts WHERE meeting_id = ?", id);
        int validChars = 0;
        for (int i = 0; i < segments.size(); i++) {
            var seg = segments.get(i);
            String text = String.valueOf(seg.get("text"));
            validChars += text.replaceAll("\\s", "").length();
            jdbc.update(
                "INSERT INTO meeting_transcripts (id, meeting_id, store_id, speaker, content, original_content, start_time, end_time, seq, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString().replace("-", ""),
                id, storeId,
                seg.get("speaker"), seg.get("text"), seg.get("text"),
                seg.get("start"), seg.get("end"),
                i,
                OffsetDateTime.now().toString(), OffsetDateTime.now().toString()
            );
        }
        log.info("转写结果已落库: meeting={}, 句数={}, 有效字数={}", id, segments.size(), validChars);

        jdbc.update("""
            UPDATE meetings
            SET transcript_status = 'done', status = 'analyzing', asr_poll_failures = 0,
                asr_error_code = NULL, fail_reason = NULL, updated_at = NOW()
            WHERE id = ? AND transcript_status = 'finalizing'
            """, id);
        return Map.of("status", "analyzing");
    }

    private Map<String, Object> recordTranscriptionPollFailure(String meetingId) {
        return recordTranscriptionPollFailure(meetingId, "正在重试查询语音识别结果，请稍候。", "poll_unavailable");
    }

    private Map<String, Object> recordTranscriptionPollFailure(String meetingId, String message, String errorCode) {
        Integer failures = jdbc.queryForObject(
            "SELECT COALESCE(asr_poll_failures, 0) FROM meetings WHERE id = ?", Integer.class, meetingId);
        if (failures != null && failures >= 4) {
            return failTranscription(meetingId, "语音识别服务连续查询失败，请检查网络后重新提交转写。", errorCode);
        }
        jdbc.update("""
            UPDATE meetings
            SET asr_poll_failures = COALESCE(asr_poll_failures, 0) + 1,
                asr_last_polled_at = NOW(), asr_error_code = ?, fail_reason = ?, updated_at = NOW()
            WHERE id = ?
            """, errorCode, message, meetingId);
        return currentStatus(meetingId);
    }

    private Map<String, Object> failTranscription(String meetingId, String reason) {
        return failTranscription(meetingId, reason, "transcription_failed");
    }

    private Map<String, Object> failTranscription(String meetingId, String reason, String errorCode) {
        jdbc.update("""
            UPDATE meetings
            SET status = 'failed', transcript_status = 'failed', asr_retry_at = NULL,
                asr_error_code = ?, fail_reason = ?, updated_at = NOW()
            WHERE id = ?
            """, errorCode, reason, meetingId);
        log.warn("转写结果失败: meeting={}, errorCode={}, reason={}", meetingId, errorCode, reason);
        return Map.of("status", "failed", "error", reason);
    }

    /**
     * DashScope 已完成识别时，下载结果文件可能短暂失败；此时绝不能误判成“无人说话”。
     * 释放 finalizing 锁后继续读取同一个任务结果，不会重复提交录音或重复计费。
     */
    private Map<String, Object> recordTranscriptionResultFailure(String meetingId, String message, String errorCode) {
        Integer failures = jdbc.queryForObject(
            "SELECT COALESCE(asr_poll_failures, 0) FROM meetings WHERE id = ?", Integer.class, meetingId);
        if (failures != null && failures >= 4) {
            return failTranscription(meetingId, "转写结果连续下载失败，请检查网络后重新提交转写。", errorCode);
        }
        jdbc.update("""
            UPDATE meetings
            SET status = 'transcribing', transcript_status = 'transcribing',
                asr_poll_failures = COALESCE(asr_poll_failures, 0) + 1,
                asr_last_polled_at = NOW(), asr_error_code = ?, fail_reason = ?, updated_at = NOW()
            WHERE id = ?
            """, errorCode, message, meetingId);
        return currentStatus(meetingId);
    }

    /**
     * 模型不可用或返回非法结构时的受控重试。
     *
     * <p>逐字稿和录音均已落库，因此这里绝不重新上传或重新提交 ASR；只把会谈留在
     * analyzing 状态并记录下一次可领取时间。三次仍失败才交给用户手动重新分析，
     * 防止后台无限调用模型、也避免空报告污染任务和客户记忆。</p>
     */
    private Map<String, Object> scheduleAnalysisRetry(String meetingId, String errorCode,
                                                       String userMessage, Exception error) {
        Integer current = jdbc.queryForObject(
            "SELECT COALESCE(analysis_attempts, 0) FROM meetings WHERE id = ?", Integer.class, meetingId);
        int attempts = (current == null ? 0 : current) + 1;
        if (attempts >= maxAnalysisAttempts) {
            String reason = "AI 分析连续失败 " + maxAnalysisAttempts + " 次，逐字稿已保留。请检查模型服务后点击“重新分析”。";
            jdbc.update("""
                UPDATE meetings
                SET status = 'failed', analysis_status = 'failed', analysis_attempts = ?,
                    analysis_retry_at = NULL, analysis_error_code = ?, fail_reason = ?, updated_at = NOW()
                WHERE id = ? AND status = 'analyzing'
                """, attempts, errorCode, reason, meetingId);
            log.warn("分析结果失败(达到重试上限): meeting={}, errorCode={}, reason={}, error={}",
                meetingId, errorCode, reason, error == null ? "" : error.getMessage());
            return Map.of("status", "failed", "error", reason);
        }

        int delaySeconds = switch (attempts) {
            case 1 -> 30;
            case 2 -> 90;
            default -> 180;
        };
        String detail = userMessage + "（第 " + attempts + "/" + maxAnalysisAttempts + " 次，约 " + delaySeconds + " 秒后重试）";
        jdbc.update("""
            UPDATE meetings
            SET status = 'analyzing', analysis_status = 'pending', analysis_attempts = ?,
                analysis_retry_at = DATE_ADD(NOW(), INTERVAL ? SECOND), analysis_error_code = ?,
                fail_reason = ?, updated_at = NOW()
            WHERE id = ? AND status = 'analyzing'
            """, attempts, delaySeconds, errorCode, detail, meetingId);
        log.warn("会谈分析待重试: meeting={}, attempt={}, code={}, error={}", meetingId, attempts, errorCode,
            error == null ? "" : error.getMessage());
        return currentStatus(meetingId);
    }

    private Map<String, Object> currentStatus(String meetingId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT status, transcript_status, fail_reason, asr_error_code, asr_retry_at,
                   asr_submit_attempts, asr_poll_failures, analysis_status,
                   analysis_attempts, analysis_retry_at, analysis_error_code
            FROM meetings WHERE id = ?
            """, meetingId);
        if (rows.isEmpty()) return Map.of("status", "failed", "error", "会谈不存在");
        Map<String, Object> row = rows.get(0);
        String status = String.valueOf(row.get("status"));
        if ("failed".equals(status)) {
            Object reason = row.get("fail_reason");
            return Map.of("status", status, "error", reason == null ? "处理失败" : String.valueOf(reason));
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status);
        payload.put("transcript_status", row.get("transcript_status"));
        payload.put("message", row.get("fail_reason"));
        payload.put("error_code", row.get("asr_error_code"));
        payload.put("retry_at", row.get("asr_retry_at"));
        payload.put("submit_attempts", row.get("asr_submit_attempts"));
        payload.put("poll_failures", row.get("asr_poll_failures"));
        payload.put("analysis_status", row.get("analysis_status"));
        payload.put("analysis_attempts", row.get("analysis_attempts"));
        payload.put("analysis_retry_at", row.get("analysis_retry_at"));
        payload.put("analysis_error_code", row.get("analysis_error_code"));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = asObjectMap(item);
            if (!map.isEmpty()) rows.add(map);
        }
        return rows;
    }

    private String userFacingProcessError(String status, Exception error) {
        String detail = error.getMessage() == null ? "" : error.getMessage();
        if ("transcribing".equals(status)) return "转写结果处理失败，请重新提交转写。";
        if ("analyzing".equals(status)) return "会谈分析失败，请稍后重新分析。";
        return detail.isBlank() ? "会谈处理失败，请稍后重试。" : detail;
    }

    private Map<String, Object> handleAnalyzing(Map<String, Object> row) throws Exception {
        String id = (String) row.get("id");
        String storeId = (String) row.get("store_id");
        String requestedStatus = safeStr(row.get("analysis_status"));
        boolean reprocessing = "reprocessing".equals(requestedStatus);

        // 正在执行中的状态不能再次领取，避免定时器与页面请求并发调用模型。
        if (!reprocessing && !"pending".equals(requestedStatus) && !requestedStatus.isBlank()) {
            return currentStatus(id);
        }

        // 浏览器手动推进和定时任务都会调用这里。先原子领取本次处理权，防止两者
        // 同时请求模型、互相覆盖报告，或其中一个把状态提前改成 done。
        String workingStatus = reprocessing ? "reprocessing_running" : "analyzing_running";
        int claimed = jdbc.update("""
            UPDATE meetings SET analysis_status = ?, updated_at = NOW()
            WHERE id = ? AND store_id = ? AND status = 'analyzing'
              AND (analysis_status = ? OR (analysis_status IS NULL AND ? = ''))
            """, workingStatus, id, storeId, requestedStatus, requestedStatus);
        if (claimed != 1) return currentStatus(id);
        log.info("开始会谈分析: meeting={}, reprocessing={}", id, reprocessing);

        // 常规流程幂等；逐句原文被人工修订后，显式进入 reprocessing 才会覆盖报告。
        var existing = jdbc.queryForList(
            "SELECT id, followup_goal, suggested_script, suggested_followup_at, report " +
                "FROM meeting_analysis WHERE meeting_id = ? AND store_id = ? " +
                "ORDER BY updated_at DESC, created_at DESC LIMIT 1", id, storeId);
        if (!existing.isEmpty() && !reprocessing) {
            jdbc.update("""
                UPDATE meetings
                SET status = 'done', analysis_status = 'done', fail_reason = NULL,
                    analysis_attempts = 0, analysis_retry_at = NULL, analysis_error_code = NULL, updated_at = NOW()
                WHERE id = ?
                """, id);
            return Map.of("status", "done");
        }

        // 读取转写
        var transcripts = jdbc.queryForList(
            "SELECT * FROM meeting_transcripts WHERE meeting_id = ? AND store_id = ? ORDER BY seq ASC", id, storeId);
        if (transcripts.isEmpty()) {
            throw new BizException("没有转写内容可分析");
        }

        // 有效证据门槛：即使存在转写记录行，也要校验"有效字数 + 有效轮次 + 有效说话时长"，
        // 避免语音识别只产生空片段/极短片段时，AI 仍基于极少证据给出客户需求、顾虑与行动建议。
        String insuffEvidence = insufficientEvidenceReason(transcripts);
        if (insuffEvidence != null) {
            // 证据不足不是"录音坏了"，不应无限重试。直接落库失败态并返回，供员工补录/修订后重试。
            jdbc.update("""
                UPDATE meetings
                SET status = 'failed', analysis_status = 'failed', fail_reason = ?, updated_at = NOW()
                WHERE id = ? AND status = 'analyzing'
                """, insuffEvidence, id);
            return Map.of("status", "failed", "error", insuffEvidence);
        }

        // ③ 说话人角色映射：speaker_x → 员工 / 客户
        Map<String, String> roleLabel = mapSpeakers(transcripts);

        // 构建带角色标注的逐句文本
        List<String> lines = new ArrayList<>();
        for (var t : transcripts) {
            String sp = (String) t.get("speaker");
            String content = (String) t.get("content");
            lines.add("[" + roleLabel.getOrDefault(sp, sp) + "] " + content);
        }

        String scene = (String) row.get("scene");
        String roleHint = "说话人已按角色标注：[员工] 为门店销售顾问，[客户] 为到店顾客。分析时请据此区分双方立场。";
        String rawText = buildRawText(transcripts);
        List<KnowledgeRetrieveService.RetrievedChunk> knowledge = retrieveMeetingKnowledge(
            storeId, safeStr(row.get("employee_id")), scene, rawText);
        String employeeRole = resolveMeetingEmployeeRole(storeId, safeStr(row.get("employee_id")));
        List<SystemPlaybookService.PlaybookReference> methodology = systemPlaybookService.search(
            (scene == null ? "" : scene) + "\n" + clip(rawText, MEETING_KNOWLEDGE_QUERY_LIMIT), employeeRole, 3);
        String knowledgeContext = buildKnowledgeContext(knowledge, methodology);

        // ① + ② 结构化输出 + 分段分析。会谈报告和 AI 教练使用同一套已启用资料，
        // 但只传与本次场景、客户表达最相关的片段，避免泛泛套用整库话术。
        Map<String, Object> analysis = analyze(lines, scene, roleHint, knowledgeContext);
        if (analysis == null) {
            return scheduleAnalysisRetry(id, "model_invalid_response",
                "AI 未返回可用的结构化分析，逐字稿已保留，系统会自动重试。", null);
        }
        log.info("会谈模型分析完成: meeting={}, fieldCount={}, knowledgeHits={}, methodologyHits={}",
            id, analysis.size(), knowledge.size(), methodology.size());

        // ④ 合规风险硬规则扫描（在 AI 文本基础上交叉验证）
        analysis.put("hard_compliance_level", 0);
        // 合规表述只评价门店一侧。若说话人尚不能可靠区分，才退回全量转写，避免
        // 客户复述“是不是最好/能否保证”等问题被错算成员工违规。
        String complianceText = buildComplianceText(transcripts, roleLabel, rawText);
        var hits = complianceScanner.scan(complianceText);
        if (!hits.isEmpty()) {
            mergeCompliance(analysis, hits);
        }

        // 模型偶尔仍会把字段输出为数组。统一转换为段落文本再入库，避免页面显示 ["…"]。
        normalizeNarrativeFields(analysis);
        analysis.put("knowledge_basis", buildKnowledgeBasis(knowledge));
        analysis.put("knowledge_hit_count", knowledge.size());
        // 报告中保存的是本次分析实际送入模型的“证据快照”，而不是事后再按标题模糊匹配。
        // 因此资料后续被修改、停用或删除时，历史复盘仍能说明当时依据了哪段内容；
        // 同时前端可以用 document_id 回到受权限保护的原资料。
        analysis.put("knowledge_sources", buildKnowledgeSources(knowledge));
        analysis.put("methodology_basis", systemPlaybookService.basis(methodology));
        analysis.put("methodology_hit_count", methodology.size());
        analysis.put("methodology_sources", buildMethodologySources(methodology));
        analysis.put("evidence_snapshot_at", OffsetDateTime.now().toString());
        analysis.put("evidence_policy", "事实判断以本次逐句转写为准；门店资料用于校准本店口径；系统销售方法论仅用于沟通与决策策略，不替代门店价格、服务或合规规则。");

        // ⑤ 量化评分：固定量表 + 可见公式 + 合规硬风险否决。
        // 缺少模型维度时明确标记“评估不完整”，绝不再伪造默认 60 分。
        MeetingQualityScorer.Result qualityResult = meetingQualityScorer.evaluate(analysis);
        Integer qualityScore = qualityResult.score();
        FollowupPlan previousPlan = reprocessing && !existing.isEmpty()
            ? persistedFollowupPlan(existing.get(0)) : FollowupPlan.empty();
        FollowupPlan nextPlan = followupPlan(analysis);

        // 提取 JSON 文本用于落库
        String jsonStr = jsonMapper.writeValueAsString(analysis);

        String now = OffsetDateTime.now().toString();
        String analysisId;
        if (reprocessing && !existing.isEmpty()) {
            analysisId = String.valueOf(existing.get(0).get("id"));
            int updated = jdbc.update("""
                UPDATE meeting_analysis
                SET report = ?, summary = ?, explicit_needs = ?, implicit_needs = ?, emotional_needs = ?,
                    decision_barriers = ?, employee_did_well = ?, employee_to_improve = ?, missed_opportunities = ?,
                    compliance_risks = ?, compliance_hits = ?, followup_goal = ?, suggested_followup_at = ?, suggested_script = ?,
                    need_manager_involved = ?, need_digging_score = ?, deal_advancing_score = ?, compliance_score = ?,
                    service_score = ?, quality_score = ?, updated_at = ?
                WHERE id = ? AND meeting_id = ? AND store_id = ?
                """,
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
                safeStr(analysis.get("compliance_hits")),
                safeStr(analysis.get("followup_goal")),
                nextPlan.dueAt() == null ? null : nextPlan.dueAt().toString(),
                safeStr(analysis.get("suggested_script")),
                toIntFlag(analysis.get("need_manager_involved")),
                nullableScore(analysis.get("need_digging_score")),
                nullableScore(analysis.get("deal_advancing_score")),
                nullableScore(analysis.get("compliance_score")),
                nullableScore(analysis.get("service_score")),
                qualityScore, now, analysisId, id, storeId);
            log.info("会谈报告已覆盖: meeting={}, analysis={}, affected={}", id, analysisId, updated);
        } else {
            analysisId = UUID.randomUUID().toString().replace("-", "");
            jdbc.update(
                "INSERT INTO meeting_analysis (id, meeting_id, store_id, report, summary, explicit_needs, implicit_needs, emotional_needs, decision_barriers, employee_did_well, employee_to_improve, missed_opportunities, compliance_risks, compliance_hits, followup_goal, suggested_followup_at, suggested_script, need_manager_involved, need_digging_score, deal_advancing_score, compliance_score, service_score, quality_score, distilled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                safeStr(analysis.get("compliance_hits")),
                safeStr(analysis.get("followup_goal")),
                nextPlan.dueAt() == null ? null : nextPlan.dueAt().toString(),
                safeStr(analysis.get("suggested_script")),
                toIntFlag(analysis.get("need_manager_involved")),
                nullableScore(analysis.get("need_digging_score")),
                nullableScore(analysis.get("deal_advancing_score")),
                nullableScore(analysis.get("compliance_score")),
                nullableScore(analysis.get("service_score")),
                qualityScore,
                0,
                now, now
            );
            log.info("会谈报告已创建: meeting={}, analysis={}", id, analysisId);
        }

        // 分析结果日志：质量评分 + 各维度 + 合规命中 + 知识/方法论命中，便于排查分析质量与 token 使用
        log.info("会谈分析结果: meeting={}, analysis={}, 质量评分={}, 需求挖掘={}, 成交推进={}, 合规={}, 服务体验={}, " +
                "合规命中={}, 需店长介入={}, 门店资料命中={}, 方法论命中={}, 是否重分析={}",
            id, analysisId, qualityScore,
            nullableScore(analysis.get("need_digging_score")),
            nullableScore(analysis.get("deal_advancing_score")),
            nullableScore(analysis.get("compliance_score")),
            nullableScore(analysis.get("service_score")),
            analysis.get("compliance_hits"),
            toIntFlag(analysis.get("need_manager_involved")),
            knowledge.size(), methodology.size(), reprocessing);

        jdbc.update("""
            UPDATE meetings
            SET status = 'done', analysis_status = 'done', quality_score = ?, fail_reason = NULL,
                analysis_attempts = 0, analysis_retry_at = NULL, analysis_error_code = NULL, updated_at = NOW()
            WHERE id = ?
            """, qualityScore, id);

        // 人工修订后的重新分析不会直接覆盖既有业务动作；若下一步计划发生变化，
        // 生成一个可追溯的确认任务，等待员工明确选择应用或保留旧计划。
        if (reprocessing) {
            queueFollowupReconciliation(id, storeId, analysisId, row, previousPlan, nextPlan);
            return Map.of("status", "done");
        }

        // ⑥ 完整闭环：经验沉淀 + 跟进任务 + 合规整改 + 店长通知 + 低分告警 + 客户记忆 + 知识缺口
        closeLoop(id, storeId, analysisId, row, analysis, qualityResult);

        return Map.of("status", "done");
    }

    // ===================== 分析核心 =====================

    /**
     * 对外分析入口：文本较短直接单次分析；过长则分段提取要素 + 汇总合并。
     */
    private Map<String, Object> analyze(List<String> lines, String scene, String roleHint, String knowledgeContext) {
        String full = String.join("\n", lines);
        if (full.length() <= SINGLE_LIMIT) {
            Map<String, Object> r = doAnalysisCall(full, scene, roleHint, knowledgeContext, false);
            if (r != null) return r;
            return null;
        }

        // ② 分段分析：按 segment 边界切分，每段独立提取要素，最后合并
        List<List<String>> chunks = splitByBoundary(lines, CHUNK_LIMIT);
        List<Map<String, Object>> partials = new ArrayList<>();
        for (var chunk : chunks) {
            Map<String, Object> part = doAnalysisCall(String.join("\n", chunk), scene, roleHint, knowledgeContext, true);
            if (part != null) partials.add(part);
        }
        // 长会谈任一段遗漏都会让综合结论失真；宁可保留转写并重试，也不能以残缺
        // 片段生成“已完成”的分析报告。
        if (partials.size() != chunks.size()) return null;

        Map<String, Object> merged = mergePartials(partials, scene, roleHint, knowledgeContext);
        return merged;
    }

    /**
     * 单次分析调用（结构化 JSON 输出）。
     * @param isPartial true=分段提取（允许信息不完整），false=完整分析
     */
    private Map<String, Object> doAnalysisCall(String text, String scene, String roleHint,
                                                String knowledgeContext, boolean isPartial) {
        String system = buildAnalysisSystem();
        String user = String.format(
            "会谈场景：%s\n%s\n\n%s\n\n转写内容：\n%s\n\n%s",
            scene,
            roleHint,
            knowledgeContext,
            text,
            isPartial
                ? "以上仅为会谈的其中一段（可能不完整）。请就这一段内容尽可能提取以下字段；没有相关信息的字段填空字符串。所有叙述字段必须是自然中文段落，不能使用数组、方括号、引号或 Markdown。必须输出 JSON。"
                : "请基于完整会谈内容，输出包含以下所有字段的 JSON 分析报告："
                  + "\nsummary, explicit_needs, implicit_needs, emotional_needs, decision_barriers, "
                  + "employee_did_well, employee_to_improve, missed_opportunities, service_experience_risk, "
                  + "compliance_risks, customer_decision_stage, judgement_basis, professional_assessment, next_step_plan, "
                  + "followup_goal, suggested_followup_at, suggested_script, need_manager_involved, "
                  + "need_digging_score, need_digging_evidence, deal_advancing_score, deal_advancing_evidence, "
                  + "compliance_score, compliance_evidence, service_score, service_evidence"
        );

        String aiResult = aiAdapter.callJson(system, user);
        if (aiResult == null) return null;

        // ① 结构化解析：response_format 已保证合法 JSON，仍保留截取兜底
        String jsonStr = extractJson(aiResult);
        try {
            Map<String, Object> m = jsonMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            if (m != null && !m.isEmpty()) return m;
        } catch (Exception e) {
            log.warn("分析 JSON 解析失败，原样保存: {}", aiResult);
        }
        return null;
    }

    /**
     * 合并多段提取结果，产出最终综合报告。
     */
    private Map<String, Object> mergePartials(List<Map<String, Object>> partials, String scene,
                                              String roleHint, String knowledgeContext) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partials.size(); i++) {
            sb.append("=== 第 ").append(i + 1).append(" 段提取结果 ===\n");
            try {
                sb.append(jsonMapper.writeValueAsString(partials.get(i)));
            } catch (Exception e) {
                sb.append(partials.get(i).toString());
            }
            sb.append("\n");
        }

        String system = buildAnalysisSystem();
        String user = String.format(
            "会谈场景：%s\n%s\n\n%s\n\n下面是一段会谈被切分后、各段分别提取出的分析要素。请将这些要素去重、合并、归纳，"
            + "产出一份完整的综合报告，必须输出 JSON，字段与单次分析完全一致：\n"
            + "summary, explicit_needs, implicit_needs, emotional_needs, decision_barriers, "
            + "employee_did_well, employee_to_improve, missed_opportunities, service_experience_risk, "
            + "compliance_risks, customer_decision_stage, judgement_basis, professional_assessment, next_step_plan, "
            + "followup_goal, suggested_followup_at, suggested_script, need_manager_involved, "
            + "need_digging_score, need_digging_evidence, deal_advancing_score, deal_advancing_evidence, "
            + "compliance_score, compliance_evidence, service_score, service_evidence\n\n"
            + "各段要素如下：\n%s",
            scene, roleHint, knowledgeContext, sb
        );

        String aiResult = aiAdapter.callJson(system, user);
        if (aiResult == null) return null;
        String jsonStr = extractJson(aiResult);
        try {
            Map<String, Object> m = jsonMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            if (m != null && !m.isEmpty()) return m;
        } catch (Exception e) {
            log.warn("合并 JSON 解析失败: {}", aiResult);
        }
        return null;
    }

    // ===================== 说话人角色映射（③） =====================

    /**
     * 启发式判定说话人角色：提问比例更高者判为员工（销售），其余为客户。
     * 平局时首位发言者为员工。单人对话统一标为客户。
     */
    private Map<String, String> mapSpeakers(List<Map<String, Object>> transcripts) {
        Map<String, String> label = new HashMap<>();
        if (transcripts.isEmpty()) return label;

        LinkedHashMap<String, Integer> qCount = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> sCount = new LinkedHashMap<>();
        String firstSpeaker = null;
        for (var t : transcripts) {
            String sp = (String) t.get("speaker");
            if (firstSpeaker == null) firstSpeaker = sp;
            sCount.merge(sp, 1, Integer::sum);
            qCount.merge(sp, countQuestions((String) t.get("content")), Integer::sum);
        }

        List<String> speakers = new ArrayList<>(sCount.keySet());
        // 人工确认优先于启发式判断。手动标注一旦保存，后续重新分析不会再被问句比例覆盖。
        for (var t : transcripts) {
            String speaker = (String) t.get("speaker");
            String role = safeStr(t.get("speaker_role"));
            if ("employee".equals(role)) label.put(speaker, "员工");
            else if ("customer".equals(role)) label.put(speaker, "客户");
            else if ("manager".equals(role)) label.put(speaker, "店长");
            else if ("other".equals(role)) label.put(speaker, "其他");
        }
        if (label.size() == speakers.size()) return label;
        if (speakers.size() == 1) {
            label.putIfAbsent(speakers.get(0), "客户");
            return label;
        }
        if (speakers.size() >= 2) {
            String a = speakers.get(0), b = speakers.get(1);
            double ra = ratio(qCount, sCount, a);
            double rb = ratio(qCount, sCount, b);
            String employee, customer;
            if (ra > rb) { employee = a; customer = b; }
            else if (rb > ra) { employee = b; customer = a; }
            else { employee = firstSpeaker; customer = (firstSpeaker.equals(a) ? b : a); }
            label.putIfAbsent(employee, "员工");
            label.putIfAbsent(customer, "客户");
        }
        return label;
    }

    private double ratio(Map<String, Integer> q, Map<String, Integer> s, String sp) {
        int sc = s.getOrDefault(sp, 0);
        if (sc == 0) return 0;
        return (double) q.getOrDefault(sp, 0) / sc;
    }

    private int countQuestions(String text) {
        if (text == null) return 0;
        int n = 0;
        n += text.replaceAll("[^?？]", "").length();
        for (String w : new String[]{"吗","呢","怎么","什么","多少","为何","为什么","哪","几","可否","是否","行不行","对不对"}) {
            int idx = 0;
            while ((idx = text.indexOf(w, idx)) != -1) { n++; idx += w.length(); }
        }
        return n;
    }

    // ===================== 工具方法 =====================

    private String buildAnalysisSystem() {
        return "你是一名资深门店销售复盘专家。你的首要证据是会谈逐句转写；随附的门店专业资料用于校准本店既定口径，系统销售方法论用于解释客户决策和沟通策略。\n"
            + "先区分事实与推断：客户明确说过的话可以写为事实；对动机的判断必须写明是推断，并给出转写依据。不得补造客户信息、成交结果或门店政策。\n"
            + "信息优先级必须遵守：合规边界高于一切；门店专属资料高于系统方法论；系统方法论不得改写或推测本店价格、活动、服务承诺和医疗/效果边界。\n"
            + "所有叙述字段必须输出为可直接阅读的自然中文字符串：可分为一到三段，但绝不能输出数组、方括号、花括号、JSON 字符串、项目符号、引号包裹的整段文字或 Markdown。没有信息时填空字符串。\n"
            + "门店专业资料和系统方法论仅在确实命中时使用。professional_assessment 必须区分写清：采用了哪一份门店资料、哪一条系统方法论，以及它们如何支撑建议；未命中门店资料时，明确说明建议不是门店既定口径。\n"
            + "请输出一个合法 JSON 对象，包含下列字段：\n"
            + "summary：一句话概括本次会谈走向与成交状态。\n"
            + "explicit_needs：客户明确表达的需求、预算、偏好或时间要求。\n"
            + "implicit_needs：可合理推断但未明说的需求，并说明推断线索。\n"
            + "emotional_needs：客户希望获得的安全感、掌控感、尊重或省心感。\n"
            + "decision_barriers：阻碍下一步成交的具体因素及优先级。\n"
            + "employee_did_well：员工已做对的销售动作，以及为何有效。\n"
            + "employee_to_improve：员工不足之处、影响和下一次可替换的具体说法或动作。\n"
            + "missed_opportunities：本次漏问、漏确认、漏推进或漏约定的机会。\n"
            + "service_experience_risk：可能损害信任、体验或复购的风险。\n"
            + "compliance_risks：过度承诺、绝对化、夸大宣传或其他合规风险；无则空字符串。\n"
            + "customer_decision_stage：客户当前处于了解、比较、犹豫、试用、确认或其他哪个决策阶段，并说明依据。\n"
            + "judgement_basis：按需求挖掘、价值呈现、异议处理、成交推进、合规和服务体验六个维度，分别写出本次判断所依据的转写事实。\n"
            + "professional_assessment：结合命中的门店专业资料和系统销售方法论给出专业分析思路，说明为何适用、哪些表述需要谨慎；两类来源必须分别说明，若未命中门店资料，明确写出限制。\n"
            + "next_step_plan：按优先顺序给出下一步动作、负责人、时点和成功判断标准。\n"
            + "followup_goal：下一次联系最需要达成的一个目标。\n"
            + "suggested_followup_at：建议跟进时间，例如 3 天内、下周二；没有依据则空字符串。\n"
            + "suggested_script：可直接使用但不夸大承诺的下一次沟通话术。\n"
            + "need_manager_involved：布尔值，只有重大风险、高价值客户或员工无权处理时为 true。\n"
            + MeetingQualityScorer.rubricForPrompt();
    }

    /** 从模型返回中截取 JSON 片段（兜底用，正常情况下 response_format 已保证纯 JSON） */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private Map<String, Object> emptyReport() {
        Map<String, Object> m = new HashMap<>();
        m.put("summary", "");
        m.put("raw", "AI 分析未返回有效结果");
        return m;
    }

    private int toIntFlag(Object val) {
        if (val == null) return 0;
        if (val instanceof Boolean b) return b ? 1 : 0;
        if (val instanceof Number n) return n.intValue() != 0 ? 1 : 0;
        if (val instanceof String s) {
            String ls = s.trim().toLowerCase();
            return ls.equals("true") || ls.equals("是") || ls.equals("1") ? 1 : 0;
        }
        return 0;
    }

    /** 安全提取字符串：null→""，对象→JSON，字符串→原值 */
    private String safeStr(Object val) {
        if (val == null) return "";
        if (val instanceof String s) return s;
        try { return jsonMapper.writeValueAsString(val); }
        catch (Exception e) { return val.toString(); }
    }

    /** 按 segment 边界把行列表切成每段不超过 maxChars 的块（最后一块可能略超） */
    private List<List<String>> splitByBoundary(List<String> lines, int maxChars) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int curLen = 0;
        for (String line : lines) {
            if (!cur.isEmpty() && curLen + line.length() > maxChars) {
                chunks.add(cur);
                cur = new ArrayList<>();
                curLen = 0;
            }
            cur.add(line);
            curLen += line.length();
        }
        if (!cur.isEmpty()) chunks.add(cur);
        return chunks;
    }

    // ===================== ④ 合规硬规则合并 =====================

    /** 拼接转写纯文本（不含角色标注），用于知识检索和无角色兜底。 */
    private String buildRawText(List<Map<String, Object>> transcripts) {
        StringBuilder sb = new StringBuilder();
        for (var t : transcripts) {
            String c = (String) t.get("content");
            if (c != null && !c.isBlank()) sb.append(c).append("\n");
        }
        return sb.toString();
    }

    /**
     * 校验转写是否具备可分析的最低证据。返回 null 表示证据充分；
     * 否则返回给用户可见的失败原因。评估维度：有效字数、有效说话轮次、有效说话时长。
     */
    private String insufficientEvidenceReason(List<Map<String, Object>> transcripts) {
        int validChars = 0;   // 有效转写字数（去除空白）
        int validTurns = 0;   // 有效说话轮次
        double totalSec = 0D; // 有效说话时长（来自 start_time/end_time，缺失则用字数估算）
        boolean hasTime = false;

        for (var t : transcripts) {
            String content = safeStr(t.get("content"));
            if (content.isBlank()) continue;
            int chars = content.replaceAll("\\s", "").length();
            validChars += chars;
            validTurns++;
            Object st = t.get("start_time");
            Object et = t.get("end_time");
            if (st instanceof Number && et instanceof Number) {
                double s = ((Number) st).doubleValue();
                double e = ((Number) et).doubleValue();
                if (e > s) {
                    totalSec += (e - s);
                    hasTime = true;
                }
            }
        }

        // 说话轮次过低（几乎只有一句无效内容）
        if (validTurns == 0) {
            return "未识别到有效语音（录音可能太短、太嘈杂或无人说话），无法可靠分析。请补录或修订转写后重试。";
        }
        // 有效字数过少，不足支撑客户事实、评分或任务结论
        if (validChars < 20) {
            return "有效转写内容过少（仅 " + validChars + " 字），证据不足，无法可靠分析。请补录或修订转写后重试。";
        }
        // 有效说话时长过短（能拿到时间戳时按时间戳算；否则按字数估算，约每字 0.28 秒）
        double effectiveSec = hasTime ? totalSec : validChars * 0.28D;
        if (effectiveSec < 10D) {
            return "有效说话时长过短（约 " + String.format("%.0f", effectiveSec) + " 秒），证据不足，无法可靠分析。请补录或修订转写后重试。";
        }
        return null;
    }

    /** 优先只取员工/店长发言进行合规词表扫描，避免把客户提问误记为门店话术。 */
    private String buildComplianceText(List<Map<String, Object>> transcripts, Map<String, String> roleLabel,
                                       String fallbackRawText) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> transcript : transcripts) {
            String speaker = safeStr(transcript.get("speaker"));
            String role = roleLabel.getOrDefault(speaker, "");
            if (!"员工".equals(role) && !"店长".equals(role)) continue;
            String content = safeStr(transcript.get("content"));
            if (!content.isBlank()) sb.append(content).append('\n');
        }
        return sb.isEmpty() ? fallbackRawText : sb.toString();
    }

    /**
     * 会谈处理运行在后台线程，没有 CurrentUser 上下文。这里按会谈所属员工的角色检索，
     * 既能复用已启用资料，又不会越过门店和可见角色边界。
     */
    private List<KnowledgeRetrieveService.RetrievedChunk> retrieveMeetingKnowledge(
            String storeId, String employeeId, String scene, String rawText) {
        if (storeId == null || storeId.isBlank() || rawText == null || rawText.isBlank()) return List.of();
        try {
            String role = resolveMeetingEmployeeRole(storeId, employeeId);
            String query = (scene == null ? "" : scene) + "\n" + clip(rawText, MEETING_KNOWLEDGE_QUERY_LIMIT);
            return knowledgeService.searchForStoreKeywordOnly(storeId, role, query, MEETING_KNOWLEDGE_TOP_N);
        } catch (Exception e) {
            // 检索是专业分析增强，不应该因资料暂时不可用导致录音、转写或基本报告失败。
            log.warn("会谈知识检索失败，继续按转写生成基础报告: {}", e.getMessage());
            return List.of();
        }
    }

    private String resolveMeetingEmployeeRole(String storeId, String employeeId) {
        if (storeId == null || storeId.isBlank() || employeeId == null || employeeId.isBlank()) return "employee";
        try {
            List<String> roles = jdbc.queryForList("SELECT role FROM employees WHERE id = ? AND store_id = ? LIMIT 1",
                String.class, employeeId, storeId);
            return roles.isEmpty() ? "employee" : roles.get(0);
        } catch (Exception ignored) {
            return "employee";
        }
    }

    private String buildKnowledgeContext(List<KnowledgeRetrieveService.RetrievedChunk> chunks,
                                         List<SystemPlaybookService.PlaybookReference> methodology) {
        StringBuilder sb = new StringBuilder();
        if (chunks == null || chunks.isEmpty()) {
            sb.append("门店专业资料检索结果：本次未命中直接相关的已启用资料。不得把通用建议表述为门店既定口径。");
        } else {
            sb.append("门店专业资料检索结果（优先级高于系统方法论，可作为本店方法与口径依据）：\n");
            int index = 1;
            for (KnowledgeRetrieveService.RetrievedChunk chunk : chunks) {
                sb.append(index++).append(". 《").append(chunk.documentTitle()).append("》\n")
                    .append(clip(chunk.content(), MEETING_KNOWLEDGE_CHUNK_LIMIT)).append("\n\n");
            }
        }
        sb.append("\n\n").append(systemPlaybookService.promptContext(methodology, MEETING_KNOWLEDGE_CHUNK_LIMIT));
        return sb.toString().trim();
    }

    private String buildKnowledgeBasis(List<KnowledgeRetrieveService.RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "本次未检索到直接相关的已启用门店资料；专业建议仅基于本次转写，需由店长确认是否符合本店口径。";
        }
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (KnowledgeRetrieveService.RetrievedChunk chunk : chunks) {
            if (chunk.documentTitle() != null && !chunk.documentTitle().isBlank()) titles.add(chunk.documentTitle());
        }
        return "本次复盘参考了已启用门店资料：" + String.join("、", titles) + "。分析以会谈转写为事实基础，资料仅用于校准专业方法与话术边界。";
    }

    /**
     * 将本次真正命中的知识片段原样快照到 report JSON。
     * 这里不保存检索分数为“可信度”，避免把关键词匹配分误导成人工审核后的可靠程度。
     */
    private List<Map<String, Object>> buildKnowledgeSources(List<KnowledgeRetrieveService.RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (KnowledgeRetrieveService.RetrievedChunk chunk : chunks) {
            if (chunk == null || chunk.documentId() == null || chunk.documentId().isBlank()) continue;
            String dedupe = chunk.documentId() + "|" + safeStr(chunk.id());
            if (!seen.add(dedupe)) continue;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("document_id", chunk.documentId());
            source.put("chunk_id", chunk.id());
            source.put("title", safeStr(chunk.documentTitle()).isBlank() ? "未命名门店资料" : chunk.documentTitle());
            source.put("excerpt", clip(chunk.content(), 360));
            source.put("kind", "store_knowledge");
            result.add(source);
        }
        return result;
    }

    /** 系统方法论与门店资料分开快照，避免被误认为本店既定制度。 */
    private List<Map<String, Object>> buildMethodologySources(List<SystemPlaybookService.PlaybookReference> references) {
        if (references == null || references.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SystemPlaybookService.PlaybookReference reference : references) {
            if (reference == null || reference.id() == null || reference.id().isBlank() || !seen.add(reference.id())) continue;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", reference.id());
            source.put("scenario_key", reference.scenarioKey());
            source.put("title", safeStr(reference.title()).isBlank() ? "系统销售方法论" : reference.title());
            source.put("module", reference.category());
            source.put("source", reference.source());
            source.put("excerpt", clip(reference.content(), 360));
            source.put("kind", "system_methodology");
            result.add(source);
        }
        return result;
    }

    /** 模型偶尔返回数组或把数组再次编码成字符串，统一转换成适合 TEXT 字段展示的段落。 */
    private void normalizeNarrativeFields(Map<String, Object> analysis) {
        for (String field : NARRATIVE_FIELDS) {
            if (analysis.containsKey(field)) analysis.put(field, readableNarrative(analysis.get(field)));
        }
    }

    @SuppressWarnings("unchecked")
    private String readableNarrative(Object value) {
        if (value == null) return "";
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::readableNarrative).filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().map(this::readableNarrative).filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < length; i++) parts.add(readableNarrative(java.lang.reflect.Array.get(value, i)));
            return parts.stream().filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
        }

        String text = String.valueOf(value).trim();
        if ((text.startsWith("[") && text.endsWith("]")) || (text.startsWith("{") && text.endsWith("}"))) {
            try {
                Object parsed = jsonMapper.readValue(text, Object.class);
                if (!(parsed instanceof String)) return readableNarrative(parsed);
            } catch (Exception ignored) {
                // 普通文案中可能刚好含括号，不能因为无法解析而丢失内容。
            }
        }
        text = text.replace("\\r\\n", "\n").replace("\\n", "\n");
        text = text.replaceAll("(?m)^\\s*(?:[-*•·]|\\d+[.、])\\s*", "");
        text = text.replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\""))
            || (text.startsWith("“") && text.endsWith("”")))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private String clip(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    /**
     * 把词表命中合并进 AI 的合规风险字段：
     * - 命中即追加【命中合规词表】明细；
     * - 任一 L3/L4 命中 → 强制 need_manager_involved=true（硬规则优先于模型判断）。
     */
    private void mergeCompliance(Map<String, Object> analysis, List<ComplianceScanner.ComplianceHit> hits) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> hitList = new ArrayList<>();
        boolean forceManager = false;
        int maxLevel = 0;
        for (var h : hits) {
            sb.append(h.getWord()).append("（").append(h.getLevelName()).append("，").append(h.getCategory()).append("）：")
              .append(h.getContext()).append("\n");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("word", h.getWord());
            m.put("level", h.getLevel());
            m.put("level_name", h.getLevelName());
            m.put("category", h.getCategory());
            m.put("context", h.getContext());
            hitList.add(m);
            maxLevel = Math.max(maxLevel, h.getLevel());
            if (h.getLevel() >= 3) forceManager = true;
        }
        String hard = sb.toString().trim();
        String existing = readableNarrative(analysis.get("compliance_risks"));
        analysis.put("compliance_risks",
            existing.isBlank() ? "系统合规词表命中：\n" + hard : existing + "\n\n系统合规词表命中：\n" + hard);
        analysis.put("compliance_hits", hitList);
        analysis.put("hard_compliance_level", maxLevel);
        if (forceManager) analysis.put("need_manager_involved", true);
    }

    // ===================== ⑤ 量化评分 =====================

    /** scorer 已将新报告归一化到 Integer/null；此处保留 null，避免 JDBC 默认值伪装成有效分数。 */
    private Integer nullableScore(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    // ===================== ⑥ 完整闭环 =====================

    /** 低分会谈告警阈值 */
    private static final int LOW_SCORE_THRESHOLD = 50;

    /**
     * 分析完成后执行完整闭环：
     * - 所有带有明确跟进目标的会谈：生成跟进任务 + 更新客户跟进时间
     * - 达到量表经验候选线且无 L3/L4 风险：额外进入经验审核候选
     * - 合规 L3/L4：生成整改任务 + 通知店长
     * - 店长介入：通知店长
     * - 低于辅导线或命中 L3/L4：创建辅导 + 告警；评分不完整不自动贴低分标签
     * - 客户记忆：写入 memory_items（低可信度先进入待确认）
     * - 知识缺口：分析失败时记录
     * 每个子步骤独立执行；局部异常会被持久化为可见的 partial_failed，而不是
     * 让会谈页面显示完成却悄悄丢失业务动作。
     */
    private Map<String, Object> closeLoop(String meetingId, String storeId, String analysisId,
                                          Map<String, Object> row, Map<String, Object> analysis,
                                          MeetingQualityScorer.Result qualityResult) {
        jdbc.update("""
            UPDATE meetings SET closure_status = 'processing', closure_attempts = COALESCE(closure_attempts, 0) + 1,
                closure_error = NULL, updated_at = NOW() WHERE id = ? AND store_id = ?
            """, meetingId, storeId);

        String customerId = emptyToNull(safeStr(row.get("customer_id")));
        String employeeId = emptyToNull(safeStr(row.get("employee_id")));
        String scene = safeStr(row.get("scene"));
        String customerName = safeStr(row.get("customer_name"));
        String summary = safeStr(analysis.get("summary"));
        List<String> failures = new ArrayList<>();

        runClosureStep("客户跟进", failures, () -> {
            createFollowupTask(storeId, meetingId, analysisId, employeeId, customerId, analysis);
            updateCustomerFollowupAt(storeId, customerId, analysis);
        });
        if (qualityResult.canDistill()) {
            runClosureStep("经验审核", failures, () ->
                distillExperience(meetingId, analysisId, storeId, scene, customerName, employeeId, customerId, analysis));
        }
        runClosureStep("合规整改", failures, () ->
            createComplianceFixTasks(meetingId, analysisId, storeId, employeeId, customerId, customerName, analysis));
        if (Boolean.TRUE.equals(analysis.get("need_manager_involved"))
                || toIntFlag(analysis.get("need_manager_involved")) == 1) {
            runClosureStep("店长介入", failures, () -> notifyManager(storeId, employeeId, summary, analysis));
        }
        if (qualityResult.needsCoaching()) {
            runClosureStep("低分辅导", failures, () ->
                handleLowScore(meetingId, analysisId, storeId, employeeId, customerId, customerName, summary, analysis));
        }
        if (customerId != null) {
            runClosureStep("客户记忆", failures, () ->
                writeCustomerMemory(meetingId, storeId, customerId, employeeId, analysisId, analysis, qualityResult.score()));
            runClosureStep("客户时间线", failures, () ->
                customerTimelineService.addInteraction(storeId, customerId, employeeId, "meeting_analysis",
                    "会谈分析完成，质量评估：" + qualityResult.displayScore() + "，摘要：" + summary));
        }
        if (summary.isBlank() || analysis.containsKey("raw")) {
            runClosureStep("知识缺口", failures, () ->
                recordKnowledgeGap(meetingId, analysisId, storeId, employeeId, customerId, scene));
        }

        if (failures.isEmpty()) {
            jdbc.update("UPDATE meeting_analysis SET distilled = ? WHERE meeting_id = ?",
                qualityResult.canDistill() ? 1 : 0, meetingId);
            jdbc.update("UPDATE meetings SET closure_status = 'completed', closure_error = NULL, updated_at = NOW() WHERE id = ?", meetingId);
            log.info("会谈闭环完成: meeting={}, quality={}, qualityStatus={}", meetingId,
                qualityResult.score(), qualityResult.status());
            return Map.of("closure_status", "completed");
        }

        String error = String.join("；", failures);
        jdbc.update("UPDATE meetings SET closure_status = 'partial_failed', closure_error = ?, updated_at = NOW() WHERE id = ?",
            error.length() > 1800 ? error.substring(0, 1800) : error, meetingId);
        log.warn("会谈闭环部分失败: meeting={}, errors={}", meetingId, error);
        return Map.of("closure_status", "partial_failed", "error", error);
    }

    /** 由页面的“重试闭环”或后台补偿调用；不重新跑 AI 分析，也不会改写转写。 */
    public Map<String, Object> retryClosure(String meetingId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM meetings WHERE id = ?", meetingId);
        String storeId = safeStr(row.get("store_id"));
        if (!"done".equals(row.get("status"))) throw BizException.badRequest("会谈分析尚未完成，暂不能重试闭环");
        Map<String, Object> persisted = jdbc.queryForMap("""
            SELECT id, report, quality_score FROM meeting_analysis
            WHERE meeting_id = ? AND store_id = ? ORDER BY updated_at DESC LIMIT 1
            """, meetingId, storeId);
        Map<String, Object> report = parseReport(persisted.get("report"));
        if (report.isEmpty()) throw BizException.badRequest("未找到可执行的会谈分析报告");
        Integer quality = persisted.get("quality_score") instanceof Number number ? number.intValue() : null;
        MeetingQualityScorer.Result qualityResult = meetingQualityScorer.fromStoredReport(report, quality);
        return closeLoop(meetingId, storeId, safeStr(persisted.get("id")), row, report, qualityResult);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseReport(Object raw) {
        if (raw instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (raw == null) return Collections.emptyMap();
        try {
            return jsonMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private void runClosureStep(String step, List<String> failures, ClosureStep action) {
        try {
            action.run();
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            failures.add(step + "失败：" + detail);
            log.warn("会谈闭环步骤失败: step={}, reason={}", step, detail);
        }
    }

    @FunctionalInterface
    private interface ClosureStep { void run() throws Exception; }

    // ---------- A. 高分经验沉淀（改为审核任务） ----------

    private void distillExperience(String meetingId, String analysisId, String storeId, String scene,
                                   String customerName, String employeeId, String customerId, Map<String, Object> analysis) {
        String script = safeStr(analysis.get("suggested_script"));
        String didWell = safeStr(analysis.get("employee_did_well"));
        String summary = safeStr(analysis.get("summary"));

        // 优质话术仅创建审核候选；审核人通过后才会创建正式知识库文档。
        experienceReviewService.createAutomaticCandidate(
            storeId, meetingId, analysisId, scene, customerName, employeeId, summary, script, didWell);

        // 改进项仍直接生成训练任务
        String improve = safeStr(analysis.get("employee_to_improve"));
        if (!improve.isBlank() && employeeId != null) {
            createMeetingTask(storeId, meetingId, analysisId, customerId,
                "meeting_analysis", "会谈改进项 · " + (customerName == null ? "客户" : customerName),
                improve, "training", employeeId, employeeId, null);
        }
    }

    // ---------- A. 跟进任务 + 客户跟进时间 ----------

    private void createFollowupTask(String storeId, String meetingId, String analysisId,
                                    String employeeId, String customerId, Map<String, Object> analysis) {
        String goal = safeStr(analysis.get("followup_goal"));
        if (goal.isBlank() || employeeId == null) return;

        String script = safeStr(analysis.get("suggested_script"));
        String followupAt = safeStr(analysis.get("suggested_followup_at"));
        OffsetDateTime dueAt = parseFollowupAt(followupAt);

        String content = "跟进目标：" + goal;
        if (!script.isBlank()) content += "\n建议话术：" + script;
        if (!followupAt.isBlank()) content += "\n建议时间：" + followupAt;

        createMeetingTask(storeId, meetingId, analysisId, customerId,
            "meeting_analysis", "跟进：" + goal, content, "followup", employeeId, employeeId, dueAt);
    }

    private void updateCustomerFollowupAt(String storeId, String customerId, Map<String, Object> analysis) {
        if (customerId == null || customerId.isBlank()) return;
        String followupAt = safeStr(analysis.get("suggested_followup_at"));
        OffsetDateTime dueAt = parseFollowupAt(followupAt);
        if (dueAt == null) return;
        try {
            jdbc.update("UPDATE customers SET next_follow_at = ?, updated_at = NOW() WHERE id = ? AND store_id = ?",
                dueAt.toString(), customerId, storeId);
        } catch (Exception e) {
            log.debug("更新客户跟进时间失败: {}", e.getMessage());
        }
    }

    // ---------- B. 合规整改任务 ----------

    private void createComplianceFixTasks(String meetingId, String analysisId, String storeId,
                                          String employeeId, String customerId, String customerName,
                                          Map<String, Object> analysis) {
        Object hitsObj = analysis.get("compliance_hits");
        if (!(hitsObj instanceof List<?> hits) || hits.isEmpty() || employeeId == null) return;

        for (Object h : hits) {
            if (!(h instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> hit = (Map<String, Object>) raw;
            Object levelObj = hit.get("level");
            int level = levelObj instanceof Number n ? n.intValue() : 0;
            if (level < 3) continue;

            String word = String.valueOf(hit.getOrDefault("word", ""));
            String categoryName = String.valueOf(hit.getOrDefault("category", ""));
            String ctx = String.valueOf(hit.getOrDefault("context", ""));

            String content = "违规词：" + word + "\n等级：" + ComplianceScanner.levelName(level)
                + "\n分类：" + categoryName + "\n上下文：" + ctx
                + "\n客户：" + (customerName == null ? "" : customerName);

            createMeetingTask(storeId, meetingId, analysisId, customerId,
                "meeting_analysis", "合规整改：" + word, content, "compliance_fix", employeeId, employeeId, null);
        }
    }

    // ---------- C. 店长介入通知 ----------

    private void notifyManager(String storeId, String employeeId, String summary, Map<String, Object> analysis) {
        String complianceRisks = safeStr(analysis.get("compliance_risks"));
        String question = "【会谈风险】" + (summary.isBlank() ? "会谈存在需店长关注的风险" : summary);
        String suggestion = complianceRisks.isBlank() ? "请查看会谈分析报告并介入处理" : complianceRisks;

        int maxLevel = getMaxComplianceLevel(analysis);
        String riskLevel = maxLevel >= 4 ? "L4" : maxLevel >= 3 ? "L3" : "L2";

        createPendingQuestion(storeId, employeeId, question, suggestion, "会谈风险", riskLevel);
    }

    // ---------- D. 低分会谈告警 ----------

    private void handleLowScore(String meetingId, String analysisId, String storeId, String employeeId,
                                String customerId, String customerName, String summary, Map<String, Object> analysis) {
        String improve = safeStr(analysis.get("employee_to_improve"));
        if (!improve.isBlank() && employeeId != null) {
            createMeetingTask(storeId, meetingId, analysisId, customerId,
                "meeting_analysis", "低分会谈改进 · " + (customerName == null ? "" : customerName),
                improve, "training", employeeId, employeeId, null);
        }

        String question = "【低分会谈】" + (summary.isBlank() ? "会谈质量分低于50，需关注" : summary);
        createPendingQuestion(storeId, employeeId, question,
            "建议安排一对一辅导或旁听优秀员工会谈", "会谈质量", "L2");
    }

    // ---------- E. 客户记忆写入 ----------

    private void writeCustomerMemory(String meetingId, String storeId, String customerId, String employeeId,
                                     String analysisId, Map<String, Object> analysis, Integer qualityScore) {
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM memory_items WHERE source_type = 'meeting_analysis' AND source_id = ?",
            Integer.class, analysisId);
        if (existing != null && existing > 0) return;

        String confidence = confidenceLevel(qualityScore);
        // 低可信度是“候选记忆”，在员工确认前不能进入 AI 教练的客户上下文。
        String memoryStatus = "low".equals(confidence) ? "pending_review" : "confirmed";
        String now = OffsetDateTime.now().toString();

        String needs = mergeNeeds(analysis);
        if (!needs.isBlank()) {
            insertMemoryItem(storeId, customerId, employeeId, "customer", "needs", needs, confidence, memoryStatus, "meeting_analysis", analysisId, now);
            if ("low".equals(confidence)) createMemoryConfirmTask(meetingId, storeId, customerId, employeeId, "needs", needs, analysisId);
        }

        String concerns = safeStr(analysis.get("decision_barriers"));
        if (!concerns.isBlank()) {
            insertMemoryItem(storeId, customerId, employeeId, "customer", "concerns", concerns, confidence, memoryStatus, "meeting_analysis", analysisId, now);
            if ("low".equals(confidence)) createMemoryConfirmTask(meetingId, storeId, customerId, employeeId, "concerns", concerns, analysisId);
        }

        String emotional = safeStr(analysis.get("emotional_needs"));
        if (!emotional.isBlank()) {
            insertMemoryItem(storeId, customerId, employeeId, "customer", "emotional_needs", emotional, confidence, memoryStatus, "meeting_analysis", analysisId, now);
            if ("low".equals(confidence)) createMemoryConfirmTask(meetingId, storeId, customerId, employeeId, "emotional_needs", emotional, analysisId);
        }
    }

    private void createMemoryConfirmTask(String meetingId, String storeId, String customerId, String employeeId,
                                         String key, String value, String analysisId) {
        createMeetingTask(storeId, meetingId, analysisId, customerId,
            "meeting_analysis", "确认客户记忆：" + key,
            "会谈分析识别出以下客户记忆，可信度较低，请确认或修正。\n\n"
                + "类型：" + key + "\n"
                + "内容：" + value + "\n"
                + "来源会谈：" + analysisId + "\n\n"
                + "该内容尚未进入正式客户记忆。请在任务中明确确认、修正或拒绝。",
            "memory_confirm", employeeId, employeeId, null);
    }

    private void insertMemoryItem(String storeId, String customerId, String employeeId,
                                  String scope, String key, String value, String confidence, String status,
                                  String sourceType, String sourceId, String now) {
        jdbc.update(
            "INSERT INTO memory_items (id, store_id, customer_id, employee_id, scope, `key`, value, confidence, status, source_type, source_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId, customerId, employeeId,
            scope, key, value, confidence, status, sourceType, sourceId, now);
    }

    // ---------- F. 知识缺口记录 ----------

    private void recordKnowledgeGap(String meetingId, String analysisId, String storeId,
                                    String employeeId, String customerId, String scene) {
        Integer existingTask = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND source_meeting_id = ? AND source_id = ? AND type = 'knowledge_review'
            """, Integer.class, storeId, meetingId, analysisId);
        if (existingTask != null && existingTask > 0) return;
        String gapId = UUID.randomUUID().toString().replace("-", "");
        String question = (scene == null ? "未知场景" : scene) + "会谈分析信息不足，需补充相关培训资料";
        jdbc.update(
            "INSERT INTO knowledge_gaps (id, store_id, employee_id, question, status, source_type, source_id, created_at) VALUES (?, ?, ?, ?, 'pending', 'meeting_analysis', ?, ?)",
            gapId, storeId, employeeId, question, analysisId, OffsetDateTime.now().toString());

        // 自动生成分配给会谈员工的确认任务
        if (employeeId != null) {
            createMeetingTask(storeId, meetingId, analysisId, customerId,
                "meeting_analysis", "补充知识缺口：" + scene,
                "知识缺口 ID：" + gapId + "\n场景：" + scene + "\n问题：" + question + "\n请补充答案并决定是否入库。",
                "knowledge_review", employeeId, employeeId, null);
        }
    }

    // ---------- 转写修订后的行动确认 ----------

    /**
     * 逐句转写被修订后，报告可以重跑，但不能静默覆盖已创建的跟进任务或客户预约时间。
     * 只有新旧建议不同才生成确认任务；任务本身带会谈、分析和客户来源。
     */
    private void queueFollowupReconciliation(String meetingId, String storeId, String analysisId,
                                             Map<String, Object> meeting, FollowupPlan previous,
                                             FollowupPlan next) {
        if (sameFollowupPlan(previous, next)) {
            jdbc.update("""
                UPDATE tasks
                SET status = 'canceled', feedback = '重新分析后行动建议与原计划一致，无需处理', updated_at = NOW()
                WHERE store_id = ? AND source_meeting_id = ? AND type = 'followup_review'
                  AND status IN ('todo', 'doing')
                """, storeId, meetingId);
            jdbc.update("UPDATE meetings SET action_review_status = 'not_required', updated_at = NOW() WHERE id = ?", meetingId);
            return;
        }

        String employeeId = safeStr(meeting.get("employee_id"));
        String assignedTo = employeeId.isBlank() ? findManagerId(storeId) : employeeId;
        if (assignedTo == null || assignedTo.isBlank()) {
            log.warn("转写修订后的行动建议无法分配确认人: meeting={}", meetingId);
            return;
        }
        String customerId = safeStr(meeting.get("customer_id"));
        String customerName = safeStr(meeting.get("customer_name"));
        String title = "确认更新跟进计划 · " + (customerName.isBlank() ? "客户" : customerName);
        String content = followupReviewContent(previous, next);

        int updated = jdbc.update("""
            UPDATE tasks
            SET title = ?, content = ?, assigned_to = ?, customer_id = ?, source_type = 'meeting_reanalysis',
                source_id = ?, updated_at = NOW()
            WHERE store_id = ? AND source_meeting_id = ? AND type = 'followup_review'
              AND status IN ('todo', 'doing')
            """, title, content, assignedTo, emptyToNull(customerId), analysisId, storeId, meetingId);
        if (updated == 0) {
            createMeetingTask(storeId, meetingId, analysisId, emptyToNull(customerId),
                "meeting_reanalysis", title, content, "followup_review", assignedTo, employeeId, null);
        }
        jdbc.update("UPDATE meetings SET action_review_status = 'pending', updated_at = NOW() WHERE id = ?", meetingId);
    }

    /** 员工在会谈详情明确选择应用新计划或保留原计划。 */
    @Transactional
    public Map<String, Object> reconcileFollowupAction(String meetingId, String decision, String operatorEmployeeId) {
        if (!"apply".equals(decision) && !"keep".equals(decision)) {
            throw BizException.badRequest("处理方式只能是 apply 或 keep");
        }
        Map<String, Object> meeting = jdbc.queryForMap(
            "SELECT id, store_id, customer_id, customer_name, employee_id FROM meetings WHERE id = ?", meetingId);
        String storeId = safeStr(meeting.get("store_id"));
        Map<String, Object> persisted = jdbc.queryForMap(
            "SELECT id, followup_goal, suggested_script, suggested_followup_at, report FROM meeting_analysis " +
                "WHERE meeting_id = ? AND store_id = ? ORDER BY updated_at DESC LIMIT 1", meetingId, storeId);
        String analysisId = safeStr(persisted.get("id"));
        FollowupPlan plan = persistedFollowupPlan(persisted);
        String customerId = emptyToNull(safeStr(meeting.get("customer_id")));
        String customerName = safeStr(meeting.get("customer_name"));

        String result;
        if ("apply".equals(decision)) {
            if (plan.goal().isBlank()) {
                int canceled = jdbc.update("""
                    UPDATE tasks
                    SET status = 'canceled', feedback = '已确认：修订后的会谈报告不再建议继续该跟进计划', updated_at = NOW()
                    WHERE store_id = ? AND source_meeting_id = ? AND type = 'followup'
                      AND status IN ('todo', 'doing')
                    """, storeId, meetingId);
                result = canceled > 0 ? "已取消原会谈生成的待办跟进任务" : "新报告未建议跟进，未改动其他人工任务";
            } else {
                String title = "跟进：" + plan.goal();
                String content = followupContent(plan);
                List<Map<String, Object>> activeTasks = jdbc.queryForList("""
                    SELECT id FROM tasks
                    WHERE store_id = ? AND source_meeting_id = ? AND type = 'followup'
                      AND status IN ('todo', 'doing')
                    ORDER BY created_at DESC LIMIT 1
                    """, storeId, meetingId);
                if (activeTasks.isEmpty()) {
                    String assignedTo = safeStr(meeting.get("employee_id"));
                    if (assignedTo.isBlank()) assignedTo = findManagerId(storeId);
                    if (assignedTo == null || assignedTo.isBlank()) {
                        throw BizException.badRequest("找不到可分配跟进任务的员工");
                    }
                    createMeetingTask(storeId, meetingId, analysisId, customerId,
                        "meeting_reanalysis", title, content, "followup", assignedTo, operatorEmployeeId, plan.dueAt());
                    result = "已按修订后的报告新建跟进任务";
                } else {
                    jdbc.update("""
                        UPDATE tasks
                        SET title = ?, content = ?, due_at = ?, customer_id = ?, source_type = 'meeting_reanalysis',
                            source_id = ?, updated_at = NOW()
                        WHERE id = ? AND store_id = ?
                        """, title, content, plan.dueAt() == null ? null : plan.dueAt().toString(), customerId,
                        analysisId, activeTasks.get(0).get("id"), storeId);
                    result = "已更新原跟进任务";
                }
                if (customerId != null && plan.dueAt() != null) {
                    jdbc.update("UPDATE customers SET next_follow_at = ?, updated_at = NOW() WHERE id = ? AND store_id = ?",
                        plan.dueAt().toString(), customerId, storeId);
                }
            }
            jdbc.update("UPDATE meetings SET action_review_status = 'applied', updated_at = NOW() WHERE id = ?", meetingId);
        } else {
            result = "已保留原有跟进计划";
            jdbc.update("UPDATE meetings SET action_review_status = 'kept', updated_at = NOW() WHERE id = ?", meetingId);
        }

        jdbc.update("""
            UPDATE tasks
            SET status = 'done', feedback = ?, updated_at = NOW()
            WHERE store_id = ? AND source_meeting_id = ? AND type = 'followup_review'
              AND status IN ('todo', 'doing')
            """, result, storeId, meetingId);
        if (customerId != null) {
            customerTimelineService.addInteraction(storeId, customerId, operatorEmployeeId,
                "meeting_action_review", "转写修订后的跟进计划：" + result);
        }
        return Map.of("action_review_status", "apply".equals(decision) ? "applied" : "kept", "message", result);
    }

    private FollowupPlan followupPlan(Map<String, Object> analysis) {
        String goal = safeStr(analysis.get("followup_goal"));
        String script = safeStr(analysis.get("suggested_script"));
        String timeText = safeStr(analysis.get("suggested_followup_at"));
        return new FollowupPlan(goal, script, timeText, parseFollowupAt(timeText));
    }

    @SuppressWarnings("unchecked")
    private FollowupPlan persistedFollowupPlan(Map<String, Object> persisted) {
        Map<String, Object> report = Collections.emptyMap();
        Object rawReport = persisted.get("report");
        if (rawReport instanceof Map<?, ?> map) {
            report = (Map<String, Object>) map;
        } else if (rawReport != null) {
            try {
                report = jsonMapper.readValue(String.valueOf(rawReport), new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) { }
        }
        String goal = firstNonBlank(safeStr(persisted.get("followup_goal")), safeStr(report.get("followup_goal")));
        String script = firstNonBlank(safeStr(persisted.get("suggested_script")), safeStr(report.get("suggested_script")));
        String timeText = firstNonBlank(safeStr(report.get("suggested_followup_at")), safeStr(persisted.get("suggested_followup_at")));
        return new FollowupPlan(goal, script, timeText, parseFollowupAt(timeText));
    }

    private boolean sameFollowupPlan(FollowupPlan left, FollowupPlan right) {
        return normalizePlanValue(left.goal()).equals(normalizePlanValue(right.goal()))
            && normalizePlanValue(left.script()).equals(normalizePlanValue(right.script()))
            && normalizePlanValue(left.timeText()).equals(normalizePlanValue(right.timeText()));
    }

    private String followupReviewContent(FollowupPlan previous, FollowupPlan next) {
        return "逐句转写修订后，AI 重新分析出的跟进计划与原计划不同。为避免覆盖已执行的动作，请在会谈详情确认。\n\n"
            + "原计划\n" + displayPlan(previous) + "\n\n"
            + "修订后建议\n" + displayPlan(next) + "\n\n"
            + "选择“应用新计划”会更新该会谈关联的待办跟进任务；选择“保留原计划”不会改动现有任务。";
    }

    private String displayPlan(FollowupPlan plan) {
        return "跟进目标：" + displayValue(plan.goal()) + "\n"
            + "建议时间：" + displayValue(plan.timeText()) + "\n"
            + "建议话术：" + displayValue(plan.script());
    }

    private String followupContent(FollowupPlan plan) {
        String content = "跟进目标：" + plan.goal();
        if (!plan.script().isBlank()) content += "\n建议话术：" + plan.script();
        if (!plan.timeText().isBlank()) content += "\n建议时间：" + plan.timeText();
        return content;
    }

    private String displayValue(String value) {
        return value == null || value.isBlank() ? "（未建议）" : value;
    }

    private String normalizePlanValue(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record FollowupPlan(String goal, String script, String timeText, OffsetDateTime dueAt) {
        static FollowupPlan empty() { return new FollowupPlan("", "", "", null); }
    }

    // ---------- 闭环辅助方法 ----------

    /** 查找门店负责人：先找 owner，再找 manager，最后取任意员工 */
    private String findManagerId(String storeId) {
        try {
            return jdbc.queryForObject(
                "SELECT id FROM employees WHERE store_id = ? AND role = 'owner' AND status = 'active' LIMIT 1",
                String.class, storeId);
        } catch (Exception ignored) {}
        try {
            return jdbc.queryForObject(
                "SELECT id FROM employees WHERE store_id = ? AND role = 'manager' AND status = 'active' LIMIT 1",
                String.class, storeId);
        } catch (Exception ignored) {}
        try {
            return jdbc.queryForObject(
                "SELECT id FROM employees WHERE store_id = ? AND status = 'active' LIMIT 1",
                String.class, storeId);
        } catch (Exception ignored) {}
        return null;
    }

    /** 会谈自动动作统一写入来源，任务反馈可据此回到客户、分析和原始会谈。 */
    private void createMeetingTask(String storeId, String meetingId, String analysisId, String customerId,
                                   String sourceType, String title, String content, String type,
                                   String assignedTo, String createdBy, OffsetDateTime dueAt) {
        Integer existing = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND source_meeting_id = ? AND source_id = ? AND type = ? AND title = ?
            """, Integer.class, storeId, meetingId, analysisId, type,
            title.length() > 200 ? title.substring(0, 200) : title);
        if (existing != null && existing > 0) return;
        jdbc.update(
            "INSERT INTO tasks (id, store_id, customer_id, title, content, type, status, assigned_to, created_by, due_at, source_type, source_id, source_meeting_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'todo', ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId, emptyToNull(customerId),
            title.length() > 200 ? title.substring(0, 200) : title,
            content, type, assignedTo, createdBy,
            dueAt == null ? null : dueAt.toString(),
            sourceType, analysisId, meetingId,
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
    }

    private void createPendingQuestion(String storeId, String employeeId, String question,
                                       String aiSuggestion, String category, String riskLevel) {
        Integer existing = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pending_questions
            WHERE store_id = ? AND employee_id <=> ? AND question = ? AND status IN ('pending', 'open')
            """, Integer.class, storeId, employeeId, question);
        if (existing != null && existing > 0) return;
        jdbc.update(
            "INSERT INTO pending_questions (id, store_id, employee_id, question, ai_suggestion, status, category, risk_level, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId, employeeId,
            question, aiSuggestion, category, riskLevel,
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
    }

    private int getMaxComplianceLevel(Map<String, Object> analysis) {
        Object hitsObj = analysis.get("compliance_hits");
        if (!(hitsObj instanceof List<?> hits)) return 0;
        int max = 0;
        for (Object h : hits) {
            if (h instanceof Map<?, ?> hit) {
                Object lv = hit.get("level");
                if (lv instanceof Number n) max = Math.max(max, n.intValue());
            }
        }
        return max;
    }

    /** 将中文时间描述解析为 OffsetDateTime（简易实现，覆盖常见表达） */
    private OffsetDateTime parseFollowupAt(String text) {
        if (text == null || text.isBlank()) return null;
        OffsetDateTime now = OffsetDateTime.now();
        try { return OffsetDateTime.parse(text); } catch (Exception ignored) { }
        try { return java.time.LocalDateTime.parse(text.replace(' ', 'T')).atOffset(now.getOffset()); } catch (Exception ignored) { }
        if (text.contains("今天")) return now;
        if (text.contains("明天")) return now.plusDays(1);
        if (text.contains("3天") || text.contains("三天")) return now.plusDays(3);
        if (text.contains("一周") || text.contains("7天")) return now.plusDays(7);
        if (text.contains("两周") || text.contains("14天")) return now.plusDays(14);
        if (text.contains("下周")) {
            int dayOfWeek = now.getDayOfWeek().getValue();
            return now.plusDays(8 - dayOfWeek);
        }
        if (text.contains("本月") || text.contains("月底")) {
            return now.withDayOfMonth(now.toLocalDate().lengthOfMonth());
        }
        // 提取数字天数
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*天").matcher(text);
        if (m.find()) {
            try { return now.plusDays(Long.parseLong(m.group(1))); } catch (Exception ignored) {}
        }
        return now.plusDays(3);
    }

    private String confidenceLevel(Integer qualityScore) {
        if (qualityScore == null) return "low";
        if (qualityScore >= 75) return "high";
        if (qualityScore >= 50) return "medium";
        return "low";
    }

    private String mergeNeeds(Map<String, Object> analysis) {
        String explicit = safeStr(analysis.get("explicit_needs"));
        String implicit = safeStr(analysis.get("implicit_needs"));
        if (explicit.isBlank() && implicit.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        if (!explicit.isBlank()) sb.append(explicit);
        if (!implicit.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(implicit);
        }
        return sb.toString();
    }
}
