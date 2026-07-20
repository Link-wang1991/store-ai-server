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
    private static final int MAX_SUBMIT_ATTEMPTS = 3;

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

    /** 将已保存的会谈放入后台提交队列。 */
    public void queue(String meetingId) {
        try {
            meetingAsrExecutor.execute(() -> submit(meetingId));
        } catch (Exception e) {
            log.warn("ASR 队列暂不可用: meeting={}", meetingId, e);
            markRetryOrFailure(meetingId, "转写任务暂时无法排队，请稍后重试。");
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
            SET status = 'queued', transcript_status = 'pending', updated_at = NOW()
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
            ORDER BY updated_at ASC
            LIMIT 20
            """, String.class, MAX_SUBMIT_ATTEMPTS);
        ids.forEach(this::queue);
    }

    private void submit(String meetingId) {
        int claimed = jdbc.update("""
            UPDATE meetings
            SET status = 'submitting',
                transcript_status = 'submitting',
                asr_submit_attempts = COALESCE(asr_submit_attempts, 0) + 1,
                asr_submit_started_at = NOW(),
                fail_reason = NULL,
                updated_at = NOW()
            WHERE id = ?
              AND status = 'queued'
              AND asr_task_id IS NULL
            """, meetingId);
        if (claimed != 1) return;

        if (qwenKey == null || qwenKey.isBlank()) {
            markFailed(meetingId, "语音识别服务尚未配置，请联系管理员后重新提交。");
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
                    fail_reason = NULL,
                    updated_at = NOW()
                WHERE id = ?
                """, taskId, meetingId);
            log.info("ASR 已提交: meeting={}, task={}", meetingId, taskId);
        } catch (Exception e) {
            log.warn("ASR 提交失败，将按策略重试: meeting={}, reason={}", meetingId, e.getMessage());
            markRetryOrFailure(meetingId, "语音识别提交失败，系统将自动重试。");
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
            throw new IllegalStateException("DashScope 文件上传返回 " + uploadResponse.statusCode());
        }

        Map<?, ?> uploadPayload = jsonMapper.readValue(uploadResponse.body(), Map.class);
        Map<?, ?> uploadData = asMap(uploadPayload.get("data"));
        List<?> uploadedFiles = asList(uploadData.get("uploaded_files"));
        if (uploadedFiles.isEmpty()) {
            List<?> failedUploads = asList(uploadData.get("failed_uploads"));
            Object message = failedUploads.isEmpty() ? null : asMap(failedUploads.get(0)).get("message");
            String reason = message == null ? "DashScope 未返回上传文件" : String.valueOf(message);
            throw new IllegalStateException(reason);
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
            throw new IllegalStateException("DashScope 转写任务返回 " + asrResponse.statusCode());
        }
        Map<?, ?> asrPayload = jsonMapper.readValue(asrResponse.body(), Map.class);
        Object taskId = asMap(asrPayload.get("output")).get("task_id");
        if (taskId == null || String.valueOf(taskId).isBlank()) {
            throw new IllegalStateException("DashScope 未返回转写任务标识");
        }
        return String.valueOf(taskId);
    }

    private void markRetryOrFailure(String meetingId, String retryReason) {
        Integer attempts = jdbc.queryForObject(
                "SELECT COALESCE(asr_submit_attempts, 0) FROM meetings WHERE id = ?", Integer.class, meetingId);
        if (attempts != null && attempts >= MAX_SUBMIT_ATTEMPTS) {
            markFailed(meetingId, "语音识别连续提交失败，请检查网络或重新录音后再试。");
            return;
        }
        jdbc.update("""
            UPDATE meetings
            SET status = 'queued',
                transcript_status = 'pending',
                fail_reason = ?,
                updated_at = NOW()
            WHERE id = ?
            """, retryReason, meetingId);
    }

    private void markFailed(String meetingId, String reason) {
        jdbc.update("""
            UPDATE meetings
            SET status = 'failed', transcript_status = 'failed', fail_reason = ?, updated_at = NOW()
            WHERE id = ?
            """, reason, meetingId);
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
