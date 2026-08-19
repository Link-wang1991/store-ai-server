package com.storeai.meeting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.auth.entity.Employee;
import com.storeai.auth.repository.EmployeeRepository;
import com.storeai.common.dto.ApiResponse;
import com.storeai.common.util.CurrentUser;
import com.storeai.common.exception.BizException;
import com.storeai.customer.entity.Customer;
import com.storeai.customer.repository.CustomerRepository;
import com.storeai.customer.service.CustomerTimelineService;
import com.storeai.meeting.entity.Meeting;
import com.storeai.meeting.repository.MeetingRepository;
import com.storeai.meeting.service.MeetingAnalysisService;
import com.storeai.meeting.service.MeetingQualityCalibrationService;
import com.storeai.meeting.service.MeetingTranscriptionService;
import com.storeai.common.service.StorageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "会谈管理")
@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private static final long MAX_AUDIO_BYTES = 60L * 1024 * 1024;
    private static final Set<String> EDITABLE_FIELDS = Set.of(
            "customer_id", "customer_name", "duration", "status", "transcript_status", "fail_reason"
    );

    private final MeetingRepository meetingRepo;
    private final CustomerRepository customerRepo;
    private final EmployeeRepository employeeRepo;
    private final CurrentUser cur;
    private final JdbcTemplate jdbc;
    private final MeetingAnalysisService analysisService;
    private final MeetingQualityCalibrationService qualityCalibrationService;
    private final MeetingTranscriptionService transcriptionService;
    private final CustomerTimelineService customerTimelineService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    // storage.provider: local | minio，默认 local
    @Value("${storage.provider:local}")
    private String storageProvider;
    @Value("${storage.local-path:./uploads/meeting-audio}")
    private String localPath;

    @GetMapping
    public ApiResponse<List<Meeting>> list() {
        var qw = new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getStoreId, cur.storeId());
        if (!cur.isAdmin()) {
            qw.eq(Meeting::getEmployeeId, cur.employeeId());
        }
        qw.orderByDesc(Meeting::getCreatedAt);
        return ApiResponse.ok(meetingRepo.selectList(qw));
    }

    /** 获取单个会谈（含客户详情） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getById(@PathVariable String id) {
        Meeting m = requireAccessibleMeeting(id);
        Map<String, Object> result = new HashMap<>();
        result.put("id", m.getId());
        result.put("store_id", m.getStoreId());
        result.put("employee_id", m.getEmployeeId());
        result.put("customer_id", m.getCustomerId());
        result.put("scene", m.getScene());
        result.put("status", m.getStatus());
        result.put("audio_url", m.getAudioUrl());
        result.put("audio_upload_state", m.getAudioUploadState());
        result.put("audio_bytes", m.getAudioBytes());
        result.put("audio_mime_type", m.getAudioMimeType());
        result.put("audio_received_at", m.getAudioReceivedAt());
        result.put("asr_task_id", m.getAsrTaskId());
        result.put("asr_submit_attempts", m.getAsrSubmitAttempts());
        result.put("asr_submit_started_at", m.getAsrSubmitStartedAt());
        result.put("asr_poll_failures", m.getAsrPollFailures());
        result.put("asr_last_polled_at", m.getAsrLastPolledAt());
        result.put("asr_retry_at", m.getAsrRetryAt());
        result.put("asr_error_code", m.getAsrErrorCode());
        result.put("transcript_status", m.getTranscriptStatus());
        result.put("fail_reason", m.getFailReason());
        result.put("analysis_status", m.getAnalysisStatus());
        result.put("analysis_attempts", m.getAnalysisAttempts());
        result.put("analysis_retry_at", m.getAnalysisRetryAt());
        result.put("analysis_error_code", m.getAnalysisErrorCode());
        result.put("action_review_status", m.getActionReviewStatus());
        result.put("closure_status", m.getClosureStatus());
        result.put("closure_attempts", m.getClosureAttempts());
        result.put("closure_error", m.getClosureError());
        result.put("duration", m.getDuration());
        result.put("audio_duration", m.getAudioDuration());
        result.put("employee_name", m.getEmployeeName());
        result.put("customer_name", m.getCustomerName());
        result.put("ended_at", m.getEndedAt());
        result.put("created_at", m.getCreatedAt());
        result.put("updated_at", m.getUpdatedAt());
        if (m.getCustomerId() != null) {
            Customer c = customerRepo.selectById(m.getCustomerId());
            if (c != null) {
                Map<String, Object> cr = new HashMap<>();
                cr.put("id", c.getId());
                cr.put("name", c.getName());
                cr.put("phone", c.getPhone());
                cr.put("tags", c.getTags());
                cr.put("stage", c.getStage());
                cr.put("visit_count", c.getTotalVisits());
                cr.put("assigned_to", c.getAssignedTo());
                result.put("customer_records", cr);
            }
        }
        return ApiResponse.ok(result);
    }

    /** 更新会谈字段（状态、时长等） */
    @PatchMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id, @RequestBody Map<String, Object> fields) {
        Meeting m = requireAccessibleMeeting(id);
        Map<String, Object> safeFields = new HashMap<>();
        fields.forEach((key, value) -> {
            if (EDITABLE_FIELDS.contains(key)) safeFields.put(key, value);
        });
        if (safeFields.isEmpty()) throw BizException.badRequest("没有可更新的会谈字段");
        if (safeFields.containsKey("status") && !"failed".equals(safeFields.get("status"))) {
            throw BizException.badRequest("不允许手动修改该会谈状态");
        }
        if (safeFields.containsKey("transcript_status") && !"failed".equals(safeFields.get("transcript_status"))) {
            throw BizException.badRequest("不允许手动修改该转写状态");
        }

        // 如果绑定了新客户，更新 meeting 的客户名
        String newCustomerId = (String) safeFields.get("customer_id");
        if (newCustomerId != null && m.getCustomerId() != null && !newCustomerId.equals(m.getCustomerId())) {
            // 更新 meeting 的客户名字段
            var newCust = customerRepo.selectById(newCustomerId);
            if (newCust == null || !cur.storeId().equals(newCust.getStoreId())) throw BizException.notFound("客户");
            if (newCust.getName() != null) safeFields.put("customer_name", newCust.getName());

            // 旧的临时占位客户：仅当不再被任何其他会谈引用时才删除。
            // 若多条会谈共用同一个临时客户，绑定其中一条时保留该临时客户，避免其他会谈的 customer_id 失效。
            var oldCust = customerRepo.selectById(m.getCustomerId());
            if (oldCust != null && oldCust.getName() != null && oldCust.getName().startsWith("新客户")) {
                Integer refs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM meetings WHERE store_id = ? AND customer_id = ? AND id <> ?",
                    Integer.class, cur.storeId(), m.getCustomerId(), id);
                if (refs == null || refs == 0) {
                    // 先把旧占位客户的关联数据（记忆、互动时间线、任务）迁移到新客户，
                    // 再删除占位客户，避免这些数据因 customer_id 指向已删客户而丢失。
                    String oldCustId = m.getCustomerId();
                    jdbc.update("UPDATE interactions SET customer_id = ? WHERE customer_id = ? AND store_id = ?",
                        newCustomerId, oldCustId, cur.storeId());
                    jdbc.update("UPDATE tasks SET customer_id = ? WHERE customer_id = ? AND store_id = ?",
                        newCustomerId, oldCustId, cur.storeId());
                    jdbc.update("UPDATE memory_items SET customer_id = ? WHERE customer_id = ? AND store_id = ?",
                        newCustomerId, oldCustId, cur.storeId());
                    customerRepo.deleteById(oldCustId);
                }
            }
        }

        var wrapper = new UpdateWrapper<Meeting>().eq("id", id);
        safeFields.forEach((key, val) -> wrapper.set(key, val));
        wrapper.set("updated_at", OffsetDateTime.now());
        meetingRepo.update(null, wrapper);
        return ApiResponse.ok();
    }

    /** 推进会谈状态：转写 → 分析 → 完成 */
    @PostMapping("/{id}/process")
    public ApiResponse<Map<String, Object>> process(@PathVariable String id) {
        requireAccessibleMeeting(id);
        return ApiResponse.ok(analysisService.process(id));
    }

    @GetMapping("/unanalyzed-count")
    public ApiResponse<Long> unanalyzedCount() {
        var qw = new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getStoreId, cur.storeId())
                .notIn(Meeting::getStatus, "done", "failed");
        if (!cur.isAdmin()) {
            qw.eq(Meeting::getEmployeeId, cur.employeeId());
        }
        return ApiResponse.ok(meetingRepo.selectCount(qw));
    }

    /** 店长查看自动评分与人工复核的真实偏差样本；不使用未标注会谈伪造准确率。 */
    @GetMapping("/quality-calibration")
    public ApiResponse<Map<String, Object>> qualityCalibration() {
        if (!cur.isAdmin()) throw BizException.forbidden("仅店长/老板可查看评分校准数据");
        return ApiResponse.ok(qualityCalibrationService.summary(cur.storeId()));
    }

    @PostMapping
    public ApiResponse<Meeting> create(@RequestBody CreateMeetingRequest req) {
        // 如果未传入 customerId，自动创建客户（陌生客户首次接待自动沉淀）
        String customerId = req.customerId();
        String customerName = req.customerName();
        if (customerId == null || customerId.isBlank()) {
            Customer c = new Customer();
            c.setStoreId(cur.storeId());
            c.setAssignedTo(cur.employeeId());
            c.setName(customerName != null && !customerName.isBlank() ? customerName
                : "新客户 " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            c.setStage("new");
            c.setPool("today");
            c.setTotalVisits(0);
            c.setCreatedAt(OffsetDateTime.now());
            c.setUpdatedAt(OffsetDateTime.now());
            customerRepo.insert(c);
            customerId = c.getId();
        }

        Meeting m = new Meeting();
        m.setStoreId(cur.storeId());
        m.setEmployeeId(cur.employeeId());
        m.setCustomerId(customerId);
        m.setScene(req.scene());
        m.setStatus("recording");
        m.setAudioUploadState("recording");
        m.setCreatedAt(OffsetDateTime.now());
        m.setUpdatedAt(OffsetDateTime.now());
        // 查询员工名和客户名，保存到会谈记录中
        Employee emp = employeeRepo.selectById(cur.employeeId());
        if (emp != null) m.setEmployeeName(emp.getName());
        Customer cust = customerRepo.selectById(customerId);
        if (cust != null) m.setCustomerName(cust.getName());
        meetingRepo.insert(m);

        if (customerId != null) {
            customerTimelineService.addInteraction(customerId, "meeting_created",
                "新建会谈，场景：" + (req.scene() == null ? "" : req.scene()));
        }

        return ApiResponse.ok(m);
    }

    @PostMapping("/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable String id) {
        requireAccessibleMeeting(id);
        meetingRepo.deleteById(id);
        return ApiResponse.ok();
    }

    /** 标记当前员工所有 recording 状态的会谈为 failed（避免残留） */
    @PostMapping("/batch-fail-recording")
    public ApiResponse<Void> batchFailRecording() {
        var wrapper = new UpdateWrapper<Meeting>()
                .eq("store_id", cur.storeId())
                .eq("employee_id", cur.employeeId())
                .eq("status", "recording");
        wrapper.set("status", "failed");
        wrapper.set("audio_upload_state", "abandoned");
        meetingRepo.update(null, wrapper);
        return ApiResponse.ok();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Path.of(localPath));
        } catch (Exception e) {
            log.warn("无法创建录音目录 {}: {}", localPath, e.getMessage());
        }
    }

    /** 上传会谈录音；云端转写在后台队列中异步提交。 */
    @PostMapping("/{id}/audio")
    public ApiResponse<String> uploadAudio(
            @PathVariable String id,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "0") int duration) {
        Meeting m = requireAccessibleMeeting(id);
        if (file == null || file.isEmpty()) throw BizException.badRequest("没有录音文件");
        if (file.getSize() > MAX_AUDIO_BYTES) throw BizException.badRequest("录音文件超过 60MB 限制");
        try (InputStream in = file.getInputStream()) {
            String ext = getExt(file.getOriginalFilename(), file.getContentType());
            String fileName = "meeting-" + id + "." + ext;
            String filePath;

            if ("minio".equalsIgnoreCase(storageProvider)) {
                filePath = saveToMinio(fileName, in, file.getSize());
            } else {
                filePath = saveToLocal(fileName, in);
            }

            // 音频先可靠落盘，随后立即返回给客户端；后台任务负责提交和重试 ASR。
            var wrapper = new LambdaUpdateWrapper<Meeting>()
                    .eq(Meeting::getId, id)
                    .set(Meeting::getStatus, "queued")
                    .set(Meeting::getAudioUrl, filePath)
                    .set(Meeting::getAudioUploadState, "stored")
                    .set(Meeting::getAudioBytes, file.getSize())
                    .set(Meeting::getAudioMimeType, file.getContentType())
                    .set(Meeting::getAudioReceivedAt, OffsetDateTime.now())
                    .set(Meeting::getAsrTaskId, null)
                    .set(Meeting::getAsrSubmitAttempts, 0)
                    .set(Meeting::getAsrSubmitStartedAt, null)
                    .set(Meeting::getAsrPollFailures, 0)
                    .set(Meeting::getAsrLastPolledAt, null)
                    .set(Meeting::getAsrRetryAt, null)
                    .set(Meeting::getAsrErrorCode, null)
                    .set(Meeting::getTranscriptStatus, "pending")
                    .set(Meeting::getFailReason, null)
                    .set(Meeting::getUpdatedAt, OffsetDateTime.now());
            if (duration > 0) {
                wrapper.set(Meeting::getDuration, duration);
            }
            wrapper.set(Meeting::getEndedAt, OffsetDateTime.now());
            meetingRepo.update(null, wrapper);
            transcriptionService.queue(id);
            return ApiResponse.ok(filePath);
        } catch (Exception e) {
            throw new BizException("录音上传失败: " + e.getMessage());
        }
    }

    /** 使用已保存的音频重新进入转写队列；不会在 HTTP 请求中同步调用第三方服务。 */
    @PostMapping("/{id}/retry-transcription")
    public ApiResponse<Void> retryTranscription(@PathVariable String id) {
        Meeting meeting = requireAccessibleMeeting(id);
        if (meeting.getAudioUrl() == null || meeting.getAudioUrl().isBlank()) {
            throw new BizException("没有可重新提交的录音文件");
        }
        jdbc.update("""
            UPDATE meetings
            SET status = 'queued', transcript_status = 'pending', asr_task_id = NULL,
                asr_submit_attempts = 0, asr_submit_started_at = NULL,
                asr_poll_failures = 0, asr_last_polled_at = NULL,
                asr_retry_at = NULL, asr_error_code = NULL,
                fail_reason = NULL, updated_at = NOW()
            WHERE id = ?
            """, id);
        transcriptionService.queue(id);
        return ApiResponse.ok();
    }

    /**
     * 设备上传、服务端落盘、ASR 提交和分析分别显示，避免用户只看到“处理失败”却
     * 不知道该重传录音、等待自动重试，还是重新分析。
     */
    @GetMapping("/{id}/diagnostics")
    public ApiResponse<Map<String, Object>> diagnostics(@PathVariable String id) {
        Meeting meeting = requireAccessibleMeeting(id);
        Map<String, Object> result = new HashMap<>();
        result.put("meeting_id", meeting.getId());
        result.put("audio_upload_state", meeting.getAudioUploadState() == null ? "pending" : meeting.getAudioUploadState());
        result.put("audio_stored", meeting.getAudioUrl() != null && !meeting.getAudioUrl().isBlank());
        result.put("audio_bytes", meeting.getAudioBytes());
        result.put("audio_mime_type", meeting.getAudioMimeType());
        result.put("audio_received_at", meeting.getAudioReceivedAt());
        result.put("asr_task_id", meeting.getAsrTaskId());
        result.put("asr_submit_attempts", meeting.getAsrSubmitAttempts() == null ? 0 : meeting.getAsrSubmitAttempts());
        result.put("asr_poll_failures", meeting.getAsrPollFailures() == null ? 0 : meeting.getAsrPollFailures());
        result.put("asr_retry_at", meeting.getAsrRetryAt());
        result.put("asr_error_code", meeting.getAsrErrorCode());
        result.put("analysis_attempts", meeting.getAnalysisAttempts() == null ? 0 : meeting.getAnalysisAttempts());
        result.put("analysis_retry_at", meeting.getAnalysisRetryAt());
        result.put("analysis_error_code", meeting.getAnalysisErrorCode());
        result.put("status", meeting.getStatus());
        result.put("transcript_status", meeting.getTranscriptStatus());
        result.put("fail_reason", meeting.getFailReason());
        result.put("next_step", diagnosticNextStep(meeting));
        return ApiResponse.ok(result);
    }

    private String diagnosticNextStep(Meeting meeting) {
        if (meeting.getAudioUrl() == null || meeting.getAudioUrl().isBlank()) return "本机尚未确认录音已落盘：请在同一设备的会谈详情重新上传；若手机是 HTTP 地址，改用“上传已有录音”或 HTTPS 后再录制。";
        // 失败态：统一优先展示具体 fail_reason，并附上可读的错误分类。
        // 注意区分转写失败（asr_error_code）与分析失败（analysis_error_code），两者都可能让 status=failed。
        if ("failed".equals(meeting.getStatus())) {
            String specific = meeting.getFailReason();
            String code = meeting.getAsrErrorCode();
            String errorCode = code != null ? code : meeting.getAnalysisErrorCode();
            String category = describeErrorCategory(errorCode);
            if (specific != null && !specific.isBlank()) {
                // 若 fail_reason 已自带分类/括号信息则不再重复拼接，避免冗余。
                return specific;
            }
            return (category != null ? category : "处理失败") + "，录音与逐字稿已保留。";
        }
        if (meeting.getAsrRetryAt() != null) return "服务端已安排自动重试；到达重试时间前无需重复上传。";
        if (meeting.getAnalysisRetryAt() != null) return "服务端已安排自动重试分析；到达重试时间前无需重复操作。";
        if ("analyzing".equals(meeting.getStatus())) return "录音与转写已通过，正在生成业务分析；如超过 10 分钟未更新，请从运行监控处理。";
        return "链路状态正常；可在会谈详情查看逐字稿、评分和业务闭环。";
    }

    /** 把后端技术化的错误码翻译为员工可读的中文分类。 */
    private String describeErrorCategory(String code) {
        if (code == null) return "语音识别失败";
        return switch (code) {
            case "transcription_failed" -> "语音识别失败";
            case "service_authorization" -> "语音服务授权异常，请联系管理员";
            case "audio_rejected" -> "录音格式不被语音服务支持，请使用 MP4/AAC/WebM/MP3";
            case "poll_unavailable" -> "语音识别结果查询失败，多为网络问题";
            case "result_download" -> "语音识别结果下载失败，多为网络问题";
            case "asr_unconfigured" -> "语音识别服务尚未配置";
            case "analysis_exception", "model_invalid_response" -> "AI 分析暂时不可用，系统会自动重试";
            case "transcript_invalid" -> "转写内容异常，建议重新录制";
            default -> "错误码 " + code;
        };
    }

    private String saveToLocal(String fileName, InputStream data) throws Exception {
        Path target = Path.of(localPath, fileName);
        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("录音已保存到本地: {}", target);
        return target.toString();
    }

    private String saveToMinio(String fileName, InputStream data, long size) throws Exception {
        return storageService.saveMeetingAudio(fileName, data, size);
    }

    /**
     * 由上传文件名推断录音扩展名。微信上传临时文件名常常不带或带错扩展名，
     * 此时根据 Content-Type 兜底，避免默认成 webm（微信小程序 InnerAudioContext 不支持 webm，会导致能下载但无声）。
     */
    private String getExt(String name, String contentType) {
        String ext = null;
        if (name != null) {
            int i = name.lastIndexOf('.');
            String candidate = i < 0 ? null : name.substring(i + 1).toLowerCase();
            if (candidate != null && Set.of("webm", "mp4", "m4a", "aac", "mp3", "ogg", "wav").contains(candidate)) {
                ext = candidate;
            }
        }
        if (ext == null && contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("mpeg") || ct.contains("mp3")) ext = "mp3";
            else if (ct.contains("mp4")) ext = "m4a";
            else if (ct.contains("aac")) ext = "aac";
            else if (ct.contains("wav")) ext = "wav";
            else if (ct.contains("ogg")) ext = "ogg";
            else if (ct.contains("webm")) ext = "webm";
        }
        // 小程序录音 mp3 是默认最稳的，实在无法判断时优先 mp3 而非 webm
        return ext == null ? "mp3" : ext;
    }

    public record CreateMeetingRequest(String customerId, String customerName, String scene) {}
    public record ActionReconciliationRequest(String decision) {}
    public record SpeakerRoleRequest(String role) {}

    /**
     * 获取原始会谈录音。录音为私有业务数据，必须先通过会谈归属校验。
     * 前端通过同源代理携带登录态调用，避免将存储路径或 MinIO 地址暴露给浏览器。
     */
    @GetMapping("/{id}/audio")
    public ResponseEntity<InputStreamResource> getAudio(@PathVariable String id) {
        Meeting meeting = requireAccessibleMeeting(id);
        String audioUrl = meeting.getAudioUrl();
        if (audioUrl == null || audioUrl.isBlank()) throw BizException.notFound("录音文件");
        try {
            InputStream input;
            if ("minio".equalsIgnoreCase(storageProvider)) {
                input = storageService.openMeetingAudio(audioUrl);
            } else {
                Path path = Path.of(audioUrl).toAbsolutePath().normalize();
                if (!Files.isRegularFile(path)) throw BizException.notFound("录音文件");
                input = Files.newInputStream(path);
            }
            String filename = Path.of(audioUrl).getFileName().toString();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(audioMediaType(filename)))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .body(new InputStreamResource(input));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取会谈录音失败: meeting={}, reason={}", id, e.getMessage());
            throw new BizException("读取录音文件失败");
        }
    }

    /** 兼容旧的带文件名录音地址。 */
    @GetMapping("/{id}/audio/{fileName}")
    public void serveAudio(@PathVariable String id, @PathVariable String fileName,
                           HttpServletResponse response) {
        requireAccessibleMeeting(id);
        Path file = Path.of(localPath, "meeting-" + id + getExtFromFile(fileName));
        if (!Files.exists(file)) {
            response.setStatus(404);
            return;
        }
        try {
            response.setContentType("audio/webm");
            response.setHeader("Content-Disposition", "inline");
            Files.copy(file, response.getOutputStream());
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    private String getExtFromFile(String name) {
        return "." + getExt(name, null);
    }

    private String audioMediaType(String filename) {
        return switch (getExt(filename, null)) {
            case "mp3" -> "audio/mpeg";
            case "m4a", "mp4" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            default -> "audio/webm";
        };
    }

    // ============================================================
    // 专用业务端点
    // ============================================================

    /** 获取会谈分析报告（先校验 meeting 归属） */
    @GetMapping("/{id}/analysis")
    public ApiResponse<List<Map<String, Object>>> getAnalysis(@PathVariable String id) {
        requireAccessibleMeeting(id);
        var rows = jdbc.queryForList(
            "SELECT * FROM meeting_analysis WHERE meeting_id = ? ORDER BY updated_at DESC, created_at DESC LIMIT 1",
            id
        );
        return ApiResponse.ok(rows);
    }

    /**
     * 店长对自动评分做业务校准。评分量表和合规红线仍保留在报告中，人工意见只作为
     * 可追溯的审核记录，不能把系统识别到的合规风险“改没”。
     */
    @PostMapping("/{id}/quality-review")
    public ApiResponse<Map<String, Object>> reviewQuality(@PathVariable String id,
                                                            @RequestBody Map<String, Object> body) {
        Meeting meeting = requireAccessibleMeeting(id);
        if (!cur.isAdmin()) throw BizException.forbidden("仅店长/老板可复核会谈评分");

        int score;
        try {
            score = Integer.parseInt(String.valueOf(body.get("score")));
        } catch (Exception ignored) {
            throw BizException.badRequest("请选择人工复核分数");
        }
        if (score != 0 && score != 25 && score != 50 && score != 75 && score != 100) {
            throw BizException.badRequest("人工复核分数仅支持 0、25、50、75、100");
        }
        String note = String.valueOf(body.getOrDefault("note", "")).trim();
        if (note.length() > 1000) throw BizException.badRequest("复核说明不能超过 1000 字");
        List<String> reasonCodes = normalizeReviewReasonCodes(body.get("reason_codes"));
        String reasonCodesJson;
        try {
            reasonCodesJson = reasonCodes.isEmpty() ? null : objectMapper.writeValueAsString(reasonCodes);
        } catch (Exception e) {
            throw BizException.badRequest("复核原因格式无效");
        }

        var rows = jdbc.queryForList("""
            SELECT id, quality_score, report FROM meeting_analysis
            WHERE meeting_id = ? AND store_id = ?
            ORDER BY updated_at DESC, created_at DESC LIMIT 1
            """, id, meeting.getStoreId());
        if (rows.isEmpty()) throw BizException.badRequest("会谈尚未生成可复核的分析报告");
        Map<String, Object> analysis = rows.get(0);
        jdbc.update("""
            UPDATE meeting_analysis
            SET quality_review_status = 'reviewed', quality_review_score = ?, quality_review_note = ?, quality_review_reason_codes = ?,
                quality_reviewed_by = ?, quality_reviewed_at = NOW(), updated_at = NOW()
            WHERE id = ? AND meeting_id = ? AND store_id = ?
            """, score, note.isBlank() ? null : note, reasonCodesJson, cur.employeeId(), analysis.get("id"), id, meeting.getStoreId());

        Map<String, Object> result = new HashMap<>();
        result.put("quality_review_status", "reviewed");
        result.put("quality_review_score", score);
        result.put("quality_review_note", note);
        result.put("quality_review_reason_codes", reasonCodes);
        result.put("quality_reviewed_by", cur.employeeId());
        result.put("quality_reviewed_at", OffsetDateTime.now());
        result.put("quality_score", analysis.get("quality_score"));
        result.put("message", "人工复核已保存；自动评分、公式和合规风险保持不变。");
        return ApiResponse.ok(result);
    }

    private List<String> normalizeReviewReasonCodes(Object raw) {
        Set<String> allowed = Set.of("need_discovery", "deal_progress", "service_experience", "compliance", "transcript_quality", "other");
        List<String> result = new ArrayList<>();
        if (!(raw instanceof List<?> values)) return result;
        for (Object value : values) {
            String code = String.valueOf(value).trim();
            if (allowed.contains(code) && !result.contains(code)) result.add(code);
        }
        return result;
    }

    /** 获取会谈转写记录（先校验 meeting 归属） */
    @GetMapping("/{id}/transcripts")
    public ApiResponse<List<Map<String, Object>>> getTranscripts(@PathVariable String id) {
        requireAccessibleMeeting(id);
        var rows = jdbc.queryForList(
            "SELECT * FROM meeting_transcripts WHERE meeting_id = ? ORDER BY seq ASC",
            id
        );
        return ApiResponse.ok(rows);
    }

    /**
     * 修订一条逐句转写：原始 ASR 原文保留在 original_content，content 保存人工修订版。
     * 修订不会默默改写既有业务任务；前端会明确提示用户决定是否用修订版重新分析。
     */
    @PatchMapping("/{id}/transcripts/{transcriptId}")
    public ApiResponse<Map<String, Object>> updateTranscript(
            @PathVariable String id, @PathVariable String transcriptId,
            @RequestBody Map<String, Object> body) {
        Meeting meeting = requireAccessibleMeeting(id);
        String content = String.valueOf(body.getOrDefault("content", "")).trim();
        if (content.isBlank()) throw BizException.badRequest("转写内容不能为空");
        if (content.length() > 4_000) throw BizException.badRequest("单句转写不能超过 4000 字");

        int updated = jdbc.update("""
            UPDATE meeting_transcripts
            SET content = ?, edited_by = ?, edited_at = NOW(), updated_at = NOW()
            WHERE id = ? AND meeting_id = ? AND store_id = ?
            """, content, cur.employeeId(), transcriptId, id, meeting.getStoreId());
        if (updated != 1) throw BizException.notFound("转写句子");

        jdbc.update("""
            UPDATE meetings
            SET analysis_status = 'needs_reanalysis', updated_at = NOW()
            WHERE id = ? AND store_id = ?
            """, id, meeting.getStoreId());
        return ApiResponse.ok(Map.of("status", "needs_reanalysis", "message", "已保存修订，请确认后重新分析"));
    }

    /** 手工确认同一说话人身份；标注会在下一次分析中优先于启发式推断。 */
    @PatchMapping("/{id}/speakers/{speaker}")
    public ApiResponse<Map<String, Object>> updateSpeakerRole(
            @PathVariable String id, @PathVariable String speaker,
            @RequestBody SpeakerRoleRequest req) {
        Meeting meeting = requireAccessibleMeeting(id);
        String role = req.role() == null ? "" : req.role().trim();
        if (!Set.of("employee", "customer", "manager", "other", "").contains(role)) {
            throw BizException.badRequest("说话人角色不正确");
        }
        int updated = jdbc.update("""
            UPDATE meeting_transcripts SET speaker_role = ?, updated_at = NOW()
            WHERE meeting_id = ? AND store_id = ? AND speaker = ?
            """, role.isBlank() ? null : role, id, meeting.getStoreId(), speaker);
        if (updated == 0) throw BizException.notFound("说话人");
        jdbc.update("""
            UPDATE meetings SET analysis_status = 'needs_reanalysis', updated_at = NOW()
            WHERE id = ? AND store_id = ?
            """, id, meeting.getStoreId());
        return ApiResponse.ok(Map.of("status", "needs_reanalysis", "updated", updated));
    }

    /** 使用人工修订后的逐句原文重跑报告；既有跟进和审核任务不会被自动重复创建。 */
    @PostMapping("/{id}/reanalyze")
    public ApiResponse<Map<String, Object>> reanalyze(@PathVariable String id) {
        Meeting meeting = requireAccessibleMeeting(id);
        Integer transcriptCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM meeting_transcripts WHERE meeting_id = ? AND store_id = ?",
            Integer.class, id, meeting.getStoreId());
        if (transcriptCount == null || transcriptCount == 0) throw BizException.badRequest("没有可重新分析的逐句转写");
        jdbc.update("""
            UPDATE meetings
            SET status = 'analyzing', analysis_status = 'reprocessing', fail_reason = NULL,
                analysis_attempts = 0, analysis_retry_at = NULL, analysis_error_code = NULL, updated_at = NOW()
            WHERE id = ? AND store_id = ?
            """, id, meeting.getStoreId());
        return ApiResponse.ok(Map.of("status", "analyzing"));
    }

    /**
     * 人工修订转写后，明确决定是否将新报告的跟进建议应用到既有业务动作。
     * 重新分析本身绝不静默覆盖任务或客户跟进时间。
     */
    @PostMapping("/{id}/action-reconciliation")
    public ApiResponse<Map<String, Object>> reconcileAction(
            @PathVariable String id, @RequestBody ActionReconciliationRequest req) {
        requireAccessibleMeeting(id);
        return ApiResponse.ok(analysisService.reconcileFollowupAction(id, req.decision(), cur.employeeId()));
    }

    /** 分析报告已存在但业务动作局部失败时，只重试闭环，不重复提交语音或重跑 AI。 */
    @PostMapping("/{id}/retry-closure")
    public ApiResponse<Map<String, Object>> retryClosure(@PathVariable String id) {
        requireAccessibleMeeting(id);
        return ApiResponse.ok(analysisService.retryClosure(id));
    }

    /** 获取门店咨询场景列表（可配置，后端 store_config 表维护） */
    @GetMapping("/scenes")
    public ApiResponse<List<Map<String, Object>>> listScenes() {
        var rows = jdbc.queryForList(
            "SELECT code, display_name, sort_order FROM store_config WHERE store_id = ? AND category = 'meeting_scene' AND enabled = TRUE ORDER BY sort_order ASC",
            cur.storeId()
        );
        return ApiResponse.ok(rows);
    }

    private Meeting requireAccessibleMeeting(String id) {
        Meeting meeting = meetingRepo.selectById(id);
        boolean denied = meeting == null
                || !cur.storeId().equals(meeting.getStoreId())
                || (!cur.isAdmin() && !cur.employeeId().equals(meeting.getEmployeeId()));
        if (denied) throw BizException.notFound("会谈记录");
        return meeting;
    }
}
