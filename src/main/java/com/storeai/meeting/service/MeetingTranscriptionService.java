package com.storeai.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.net.DirectProxySelector;
import com.storeai.common.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会谈语音转写提交器。
 *
 * 上传接口只负责可靠保存音频并将会谈置为 queued；DashScope 的文件上传和
 * 转写任务提交在此服务的独立线程执行。失败会留下可诊断状态，并由定时器重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingTranscriptionService {

    private static final String DS_BASE = "https://dashscope.aliyuncs.com/api/v1";
    private static final int[] RETRY_DELAYS_SECONDS = {30, 90, 300, 900};

    private final JdbcTemplate jdbc;
    private final StorageService storageService;
    @Qualifier("meetingAsrExecutor")
    private final TaskExecutor meetingAsrExecutor;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .proxy(DirectProxySelector.INSTANCE)
            .build();

    @Value("${ai.qwen.api-key:}")
    private String qwenKey;

    @Value("${storage.provider:local}")
    private String storageProvider;

    /**
     * ASR 语音识别提交的最大尝试次数。默认 4 次；为临时关闭自动重试（避免重复上传/提交消耗费用）
     * 可配置为 1，失败一次即进入用户可见的失败态，不再自动重试。
     */
    @Value("${meeting.asr-max-submit-attempts:4}")
    private int maxSubmitAttempts;

    /** 将已保存的会谈放入后台提交队列。 */
    public void queue(String meetingId) {
        try {
            meetingAsrExecutor.execute(() -> submit(meetingId));
        } catch (Exception e) {
            log.warn("ASR 队列暂不可用: meeting={}", meetingId, e);
            markRetryOrFailure(meetingId, new FailureInfo("queue_unavailable", "转写任务暂时无法排队", false));
        }
    }

    /**
     * 恢复进程重启前未提交完成的任务，并对短暂网络失败进行后台重试。
     * 任务领取使用条件更新，重复触发不会并发重复提交同一场会谈。
     */
    @Scheduled(fixedDelayString = "${meeting.asr-retry-interval-ms:30000}")
    public void recoverQueuedMeetings() {
        jdbc.update("""
            UPDATE meetings
            SET status = 'queued', transcript_status = 'pending',
                asr_retry_at = NOW(), asr_error_code = 'submit_stalled',
                fail_reason = '转写提交超时，正在重新排队。', updated_at = NOW()
            WHERE status = 'submitting'
              AND asr_task_id IS NULL
              AND asr_submit_started_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)
            """);

        List<String> ids = jdbc.queryForList("""
            SELECT id FROM meetings
            WHERE status = 'queued'
              AND asr_task_id IS NULL
              AND audio_url IS NOT NULL
              AND COALESCE(asr_submit_attempts, 0) < ?
              AND (asr_retry_at IS NULL OR asr_retry_at <= NOW())
            ORDER BY updated_at ASC
            LIMIT 20
            """, String.class, maxSubmitAttempts);
        ids.forEach(this::queue);
    }

    private void submit(String meetingId) {
        int claimed = jdbc.update("""
            UPDATE meetings
            SET status = 'submitting',
                transcript_status = 'submitting',
                asr_submit_attempts = COALESCE(asr_submit_attempts, 0) + 1,
                asr_submit_started_at = NOW(),
                asr_poll_failures = 0,
                asr_last_polled_at = NULL,
                asr_retry_at = NULL,
                asr_error_code = NULL,
                fail_reason = NULL,
                updated_at = NOW()
            WHERE id = ?
              AND status = 'queued'
              AND asr_task_id IS NULL
            """, meetingId);
        if (claimed != 1) return;

        if (qwenKey == null || qwenKey.isBlank()) {
            markFailed(meetingId, "语音识别服务尚未配置，请联系管理员后重新提交。", "service_not_configured");
            return;
        }

        Path temporaryFile = null;
        try {
            Map<String, Object> meeting = jdbc.queryForMap(
                    "SELECT audio_url FROM meetings WHERE id = ?", meetingId);
            String audioUrl = (String) meeting.get("audio_url");
            if (audioUrl == null || audioUrl.isBlank()) {
                throw new IllegalStateException("未找到录音文件");
            }

            Path audioFile;
            if ("minio".equalsIgnoreCase(storageProvider)) {
                temporaryFile = Files.createTempFile("store-ai-asr-", extensionOf(audioUrl));
                try (InputStream in = storageService.openMeetingAudio(audioUrl)) {
                    Files.copy(in, temporaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                audioFile = temporaryFile;
            } else {
                audioFile = Path.of(audioUrl).toAbsolutePath().normalize();
            }
            if (!Files.isRegularFile(audioFile) || Files.size(audioFile) == 0) {
                throw new IllegalStateException("录音文件不存在或为空");
            }

            String taskId = submitDashScopeAsr(audioFile, audioFile.getFileName().toString());
            jdbc.update("""
                UPDATE meetings
                SET status = 'transcribing',
                    transcript_status = 'transcribing',
                    asr_task_id = ?,
                    asr_poll_failures = 0,
                    asr_last_polled_at = NULL,
                    asr_retry_at = NULL,
                    asr_error_code = NULL,
                    fail_reason = NULL,
                    updated_at = NOW()
                WHERE id = ?
                """, taskId, meetingId);
            log.info("ASR 已提交: meeting={}, task={}", meetingId, taskId);
        } catch (Exception e) {
            log.warn("ASR 提交失败，将按策略重试: meeting={}, reason={}", meetingId, e.getMessage());
            markRetryOrFailure(meetingId, classifySubmissionFailure(e));
        } finally {
            if (temporaryFile != null) {
                try { Files.deleteIfExists(temporaryFile); } catch (Exception ignored) { }
            }
        }
    }

    private String submitDashScopeAsr(Path audioFile, String fileName) throws Exception {
        String boundary = "----StoreAi" + UUID.randomUUID().toString().replace("-", "");
        String safeName = fileName.replaceAll("[\\r\\n\\\"]", "_");
        String mime = mimeFor(safeName);
        // DashScope 文件管理 API 使用复数 files 字段；旧的 file/transcription
        // 写法已不在当前接口的受支持参数范围内，会导致部分录音无法提交。
        byte[] header = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\nfile-extract\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(DS_BASE + "/files"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + qwenKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(header),
                        HttpRequest.BodyPublishers.ofFile(audioFile),
                        HttpRequest.BodyPublishers.ofByteArray(footer)))
                .build();
        HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
        if (uploadResponse.statusCode() != 200) {
            throw providerFailure("upload", uploadResponse.statusCode(), uploadResponse.body());
        }

        Map<?, ?> uploadPayload = jsonMapper.readValue(uploadResponse.body(), Map.class);
        Map<?, ?> uploadData = asMap(uploadPayload.get("data"));
        List<?> uploadedFiles = asList(uploadData.get("uploaded_files"));
        if (uploadedFiles.isEmpty()) {
            List<?> failedUploads = asList(uploadData.get("failed_uploads"));
            Object message = failedUploads.isEmpty() ? null : asMap(failedUploads.get(0)).get("message");
            String reason = message == null ? "服务未返回上传文件" : String.valueOf(message);
            throw new AsrSubmitException("audio_rejected", "录音文件未被语音服务接受", isPermanentAudioFailure(reason));
        }
        String fileId = String.valueOf(asMap(uploadedFiles.get(0)).get("file_id"));
        if (fileId.isBlank() || "null".equals(fileId)) throw new IllegalStateException("DashScope 未返回文件标识");

        String ossUrl = null;
        for (int attempt = 0; attempt < 3 && ossUrl == null; attempt++) {
            if (attempt > 0) Thread.sleep(1000L * attempt);
            HttpRequest infoRequest = HttpRequest.newBuilder()
                    .uri(URI.create(DS_BASE + "/files/" + fileId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + qwenKey)
                    .GET().build();
            HttpResponse<String> infoResponse = httpClient.send(infoRequest, HttpResponse.BodyHandlers.ofString());
            if (infoResponse.statusCode() == 200) {
                Map<?, ?> infoPayload = jsonMapper.readValue(infoResponse.body(), Map.class);
                Object url = asMap(infoPayload.get("data")).get("url");
                if (url != null) ossUrl = String.valueOf(url);
            } else if (infoResponse.statusCode() == 401 || infoResponse.statusCode() == 403) {
                throw providerFailure("file_info", infoResponse.statusCode(), infoResponse.body());
            }
        }
        if (ossUrl == null || ossUrl.isBlank()) throw new IllegalStateException("未获取到转写文件地址");

        String asrBody = jsonMapper.writeValueAsString(Map.of(
                "model", "paraformer-v2",
                "input", Map.of("file_urls", List.of(ossUrl)),
                "parameters", Map.of("language_hints", List.of("zh"), "diarization_enabled", true)
        ));
        HttpRequest asrRequest = HttpRequest.newBuilder()
                .uri(URI.create(DS_BASE + "/services/audio/asr/transcription"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + qwenKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(asrBody))
                .build();
        HttpResponse<String> asrResponse = httpClient.send(asrRequest, HttpResponse.BodyHandlers.ofString());
        if (asrResponse.statusCode() != 200) {
            throw providerFailure("asr_submit", asrResponse.statusCode(), asrResponse.body());
        }
        Map<?, ?> asrPayload = jsonMapper.readValue(asrResponse.body(), Map.class);
        Object taskId = asMap(asrPayload.get("output")).get("task_id");
        if (taskId == null || String.valueOf(taskId).isBlank()) {
            throw new IllegalStateException("DashScope 未返回转写任务标识");
        }
        return String.valueOf(taskId);
    }

    private void markRetryOrFailure(String meetingId, FailureInfo failure) {
        Integer attempts = jdbc.queryForObject(
                "SELECT COALESCE(asr_submit_attempts, 0) FROM meetings WHERE id = ?", Integer.class, meetingId);
        if (failure.terminal()) {
            markFailed(meetingId, failure.message() + "，请检查录音或联系管理员后重新提交。", failure.code());
            return;
        }
        if (attempts != null && attempts >= maxSubmitAttempts) {
            markFailed(meetingId, "语音识别多次提交未成功。请检查网络后重新提交；录音仍已保留。", failure.code());
            return;
        }
        int attempt = Math.max(1, attempts == null ? 1 : attempts);
        int delay = RETRY_DELAYS_SECONDS[Math.min(attempt - 1, RETRY_DELAYS_SECONDS.length - 1)];
        jdbc.update("""
            UPDATE meetings
            SET status = 'queued',
                transcript_status = 'pending',
                asr_poll_failures = 0,
                asr_last_polled_at = NULL,
                asr_retry_at = DATE_ADD(NOW(), INTERVAL %d SECOND),
                asr_error_code = ?,
                fail_reason = ?,
                updated_at = NOW()
            WHERE id = ?
            """.formatted(delay), failure.code(), failure.message() + "，将于约 " + delay + " 秒后自动重试。", meetingId);
    }

    private void markFailed(String meetingId, String reason, String errorCode) {
        jdbc.update("""
            UPDATE meetings
            SET status = 'failed', transcript_status = 'failed', asr_retry_at = NULL,
                asr_error_code = ?, fail_reason = ?, updated_at = NOW()
            WHERE id = ?
            """, errorCode, reason, meetingId);
    }

    private FailureInfo classifySubmissionFailure(Exception error) {
        if (error instanceof AsrSubmitException provider) {
            return new FailureInfo(provider.code, provider.getMessage(), provider.terminal);
        }
        String detail = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        if (detail.contains("未找到录音") || detail.contains("不存在或为空")) {
            return new FailureInfo("audio_missing", "未找到可提交的录音文件", true);
        }
        if (detail.contains("timeout") || detail.contains("timed out") || detail.contains("connect")
                || detail.contains("connection") || detail.contains("reset")) {
            return new FailureInfo("network_unavailable", "暂时无法连接语音识别服务", false);
        }
        return new FailureInfo("submit_unavailable", "语音识别服务暂时不可用", false);
    }

    private AsrSubmitException providerFailure(String stage, int status, String body) {
        if (status == 401 || status == 403) {
            return new AsrSubmitException("service_authorization", "语音识别服务授权异常", true);
        }
        if (status == 413) {
            return new AsrSubmitException("audio_too_large", "录音文件超过语音服务支持的大小", true);
        }
        if (status == 415 || (status >= 400 && status < 500 && status != 408 && status != 429)) {
            return new AsrSubmitException("audio_rejected", "录音格式或文件内容不被语音服务支持", true);
        }
        log.warn("DashScope {} temporary response: status={}, body={}", stage, status, safeBody(body));
        return new AsrSubmitException("service_unavailable", "语音识别服务暂时不可用", false);
    }

    private static boolean isPermanentAudioFailure(String reason) {
        String text = reason == null ? "" : reason.toLowerCase();
        return text.contains("invalid") || text.contains("unsupported") || text.contains("format") || text.contains("文件格式");
    }

    private static String safeBody(String body) {
        if (body == null) return "";
        return body.replaceAll("[\\r\\n]", " ").substring(0, Math.min(body.length(), 300));
    }

    private record FailureInfo(String code, String message, boolean terminal) { }

    private static final class AsrSubmitException extends Exception {
        private final String code;
        private final boolean terminal;

        private AsrSubmitException(String code, String message, boolean terminal) {
            super(message);
            this.code = code;
            this.terminal = terminal;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String extensionOf(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? ".webm" : name.substring(index);
    }

    private static String mimeFor(String name) {
        String ext = extensionOf(name).toLowerCase();
        return switch (ext) {
            case ".mp4", ".m4a" -> "audio/mp4";
            case ".aac" -> "audio/aac";
            case ".mp3" -> "audio/mpeg";
            case ".ogg" -> "audio/ogg";
            default -> "audio/webm";
        };
    }
}
