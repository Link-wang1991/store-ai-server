package com.storeai.meeting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
    private final MeetingTranscriptionService transcriptionService;
    private final CustomerTimelineService customerTimelineService;
    private final StorageService storageService;

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
        result.put("asr_task_id", m.getAsrTaskId());
        result.put("transcript_status", m.getTranscriptStatus());
        result.put("fail_reason", m.getFailReason());
        result.put("analysis_status", m.getAnalysisStatus());
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

        // 如果绑定了新客户，清理旧的占位客户并更新 meeting 的客户名
        String newCustomerId = (String) safeFields.get("customer_id");
        if (newCustomerId != null && m.getCustomerId() != null && !newCustomerId.equals(m.getCustomerId())) {
            var oldCust = customerRepo.selectById(m.getCustomerId());
            if (oldCust != null && oldCust.getName() != null && oldCust.getName().startsWith("新客户")) {
                customerRepo.deleteById(m.getCustomerId());
            }
            // 更新 meeting 的客户名字段
            var newCust = customerRepo.selectById(newCustomerId);
            if (newCust == null || !cur.storeId().equals(newCust.getStoreId())) throw BizException.notFound("客户");
            if (newCust.getName() != null) safeFields.put("customer_name", newCust.getName());
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
            String ext = getExt(file.getOriginalFilename());
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
                    .set(Meeting::getAsrTaskId, null)
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
                fail_reason = NULL, updated_at = NOW()
            WHERE id = ?
            """, id);
        transcriptionService.queue(id);
        return ApiResponse.ok();
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

    private String getExt(String name) {
        if (name == null) return "webm";
        int i = name.lastIndexOf('.');
        String ext = i < 0 ? "webm" : name.substring(i + 1).toLowerCase();
        return Set.of("webm", "mp4", "m4a", "aac", "mp3", "ogg").contains(ext) ? ext : "webm";
    }

    public record CreateMeetingRequest(String customerId, String customerName, String scene) {}

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
        return "." + getExt(name);
    }

    private String audioMediaType(String filename) {
        return switch (getExt(filename)) {
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
            "SELECT * FROM meeting_analysis WHERE meeting_id = ? ORDER BY created_at DESC LIMIT 1",
            id
        );
        return ApiResponse.ok(rows);
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
