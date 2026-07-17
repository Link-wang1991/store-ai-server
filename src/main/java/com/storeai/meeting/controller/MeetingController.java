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
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "会谈管理")
@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingRepository meetingRepo;
    private final CustomerRepository customerRepo;
    private final EmployeeRepository employeeRepo;
    private final CurrentUser cur;
    private final JdbcTemplate jdbc;
    private final MeetingAnalysisService analysisService;
    private final CustomerTimelineService customerTimelineService;

    // storage.provider: local | minio，默认 local
    @Value("${storage.provider:local}")
    private String storageProvider;
    @Value("${storage.local-path:./uploads/meeting-audio}")
    private String localPath;

    @Value("${ai.qwen.api-key:}")
    private String qwenKey;

    private static final String DS_BASE = "https://dashscope.aliyuncs.com/api/v1";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper jsonMapper = new ObjectMapper();

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
        Meeting m = meetingRepo.selectById(id);
        if (m == null || !cur.storeId().equals(m.getStoreId())) {
            throw BizException.notFound("会谈记录");
        }
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
        Meeting m = meetingRepo.selectById(id);
        if (m == null || !cur.storeId().equals(m.getStoreId())) {
            throw BizException.notFound("会谈记录");
        }

        // 如果绑定了新客户，清理旧的占位客户并更新 meeting 的客户名
        String newCustomerId = (String) fields.get("customer_id");
        if (newCustomerId != null && m.getCustomerId() != null && !newCustomerId.equals(m.getCustomerId())) {
            var oldCust = customerRepo.selectById(m.getCustomerId());
            if (oldCust != null && oldCust.getName() != null && oldCust.getName().startsWith("新客户")) {
                customerRepo.deleteById(m.getCustomerId());
            }
            // 更新 meeting 的客户名字段
            var newCust = customerRepo.selectById(newCustomerId);
            if (newCust != null && newCust.getName() != null) {
                fields.put("customer_name", newCust.getName());
            }
        }

        var wrapper = new UpdateWrapper<Meeting>().eq("id", id);
        fields.forEach((key, val) -> wrapper.set(key, val));
        meetingRepo.update(null, wrapper);
        return ApiResponse.ok();
    }

    /** 推进会谈状态：转写 → 分析 → 完成 */
    @PostMapping("/{id}/process")
    public ApiResponse<Map<String, Object>> process(@PathVariable String id) {
        Meeting m = meetingRepo.selectById(id);
        if (m == null || !cur.storeId().equals(m.getStoreId())) {
            throw BizException.notFound("会谈记录");
        }
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
        Meeting m = meetingRepo.selectById(id);
        if (m == null || !cur.storeId().equals(m.getStoreId())) {
            throw BizException.notFound("会谈记录");
        }
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

    /** 上传会谈录音并提交 ASR 转写 */
    @PostMapping("/{id}/audio")
    public ApiResponse<String> uploadAudio(
            @PathVariable String id,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "0") int duration) {
        Meeting m = meetingRepo.selectById(id);
        if (m == null || !cur.storeId().equals(m.getStoreId())) {
            throw BizException.notFound("会谈记录");
        }
        try (InputStream in = file.getInputStream()) {
            String ext = getExt(file.getOriginalFilename());
            String fileName = "meeting-" + id + "." + ext;
            String filePath;

            if ("minio".equals(storageProvider)) {
                filePath = saveToMinio(fileName, in, file.getSize());
            } else {
                filePath = saveToLocal(fileName, in);
            }

            // 提交 DashScope ASR
            String asrTaskId = null;
            if (qwenKey != null && !qwenKey.isBlank()) {
                try {
                    asrTaskId = submitDashScopeAsr(file);
                    log.info("ASR 已提交: meeting={}, task={}", id, asrTaskId);
                } catch (Exception e) {
                    log.error("ASR 提交失败: meeting={}, error={}", id, e.getMessage());
                }
            } else {
                log.warn("未配置 QWEN_API_KEY，跳过 ASR 提交");
            }

            // 更新状态、文件路径、时长、结束时间
            var wrapper = new LambdaUpdateWrapper<Meeting>()
                    .eq(Meeting::getId, id)
                    .set(Meeting::getStatus, "transcribing")
                    .set(Meeting::getAudioUrl, filePath)
                    .set(Meeting::getUpdatedAt, OffsetDateTime.now());
            if (duration > 0) {
                wrapper.set(Meeting::getDuration, duration);
            }
            if (asrTaskId != null) {
                wrapper.set(Meeting::getAsrTaskId, asrTaskId);
            }
            wrapper.set(Meeting::getEndedAt, OffsetDateTime.now());
            meetingRepo.update(null, wrapper);
            return ApiResponse.ok(filePath);
        } catch (Exception e) {
            throw new BizException("录音上传失败: " + e.getMessage());
        }
    }

    /** 提交音频到 DashScope ASR，返回 task_id */
    private String submitDashScopeAsr(MultipartFile file) throws Exception {
        // 1. 上传文件到 DashScope
        String boundary = "----" + System.currentTimeMillis();
        byte[] fileBytes = file.getBytes();
        String mime = file.getContentType() != null ? file.getContentType() : "audio/webm";
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.webm";

        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        String footer = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\ntranscription\r\n"
                + "--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes("UTF-8");
        byte[] footerBytes = footer.getBytes("UTF-8");
        byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);

        var upReq = HttpRequest.newBuilder()
                .uri(URI.create(DS_BASE + "/files"))
                .header("Authorization", "Bearer " + qwenKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        var upRes = httpClient.send(upReq, HttpResponse.BodyHandlers.ofString());
        if (upRes.statusCode() != 200) {
            throw new RuntimeException("DashScope 文件上传失败: " + upRes.statusCode() + " " + upRes.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> upData = jsonMapper.readValue(upRes.body(), Map.class);
        // uploaded_files 是个数组，取第一个元素的 file_id
        var upFiles = (List<Map<String, Object>>) ((Map<String, Object>) upData.get("data")).get("uploaded_files");
        String fileId = (String) upFiles.get(0).get("file_id");
        if (fileId == null) throw new RuntimeException("DashScope 未返回 file_id");

        // 2. 获取 OSS URL（带重试）
        String ossUrl = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(1000L * attempt);
            var infoReq = HttpRequest.newBuilder()
                    .uri(URI.create(DS_BASE + "/files/" + fileId))
                    .header("Authorization", "Bearer " + qwenKey)
                    .GET().build();
            var infoRes = httpClient.send(infoReq, HttpResponse.BodyHandlers.ofString());
            if (infoRes.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> infoData = jsonMapper.readValue(infoRes.body(), Map.class);
                ossUrl = (String) ((Map<String, Object>) infoData.get("data")).get("url");
                break;
            }
        }
        if (ossUrl == null) throw new RuntimeException("获取 OSS URL 失败");

        // 3. 提交 ASR 转写
        String asrBody = jsonMapper.writeValueAsString(Map.of(
                "model", "paraformer-v2",
                "input", Map.of("file_urls", List.of(ossUrl)),
                "parameters", Map.of("language_hints", List.of("zh"), "diarization_enabled", true)
        ));
        var asrReq = HttpRequest.newBuilder()
                .uri(URI.create(DS_BASE + "/services/audio/asr/transcription"))
                .header("Authorization", "Bearer " + qwenKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(asrBody))
                .build();
        var asrRes = httpClient.send(asrReq, HttpResponse.BodyHandlers.ofString());
        if (asrRes.statusCode() != 200) {
            throw new RuntimeException("ASR 提交失败: " + asrRes.statusCode() + " " + asrRes.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> asrData = jsonMapper.readValue(asrRes.body(), Map.class);
        String taskId = (String) ((Map<String, Object>) asrData.get("output")).get("task_id");
        if (taskId == null) throw new RuntimeException("ASR 未返回 task_id");
        return taskId;
    }

    private String saveToLocal(String fileName, InputStream data) throws Exception {
        Path target = Path.of(localPath, fileName);
        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("录音已保存到本地: {}", target);
        return target.toString();
    }

    private String saveToMinio(String fileName, InputStream data, long size) throws Exception {
        // 预留 MinIO 存储，引用 StorageService
        // storage.saveMeetingAudio(fileName, data, size);
        // return fileName;
        throw new UnsupportedOperationException("MinIO 存储尚未配置，请使用 local 模式");
    }

    private String getExt(String name) {
        if (name == null) return "webm";
        int i = name.lastIndexOf('.');
        return i < 0 ? "webm" : name.substring(i + 1);
    }

    public record CreateMeetingRequest(String customerId, String customerName, String scene) {}

    /**
     * 获取会谈录音文件（供 ASR 转写服务拉取）
     */
    @GetMapping("/{id}/audio/{fileName}")
    public void serveAudio(@PathVariable String id, @PathVariable String fileName,
                           HttpServletResponse response) {
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
        if (name == null) return ".webm";
        int i = name.lastIndexOf('.');
        return i < 0 ? ".webm" : name.substring(i);
    }

    // ============================================================
    // 专用业务端点
    // ============================================================

    /** 获取会谈分析报告（先校验 meeting 归属） */
    @GetMapping("/{id}/analysis")
    public ApiResponse<List<Map<String, Object>>> getAnalysis(@PathVariable String id) {
        Meeting m = meetingRepo.selectById(id);
        if (m == null || !cur.storeId().equals(m.getStoreId())) {
            throw BizException.notFound("会谈记录");
        }
        var rows = jdbc.queryForList(
            "SELECT * FROM meeting_analysis WHERE meeting_id = ? ORDER BY created_at DESC LIMIT 1",
            id
        );
        return ApiResponse.ok(rows);
    }

    /** 获取会谈转写记录（先校验 meeting 归属） */
    @GetMapping("/{id}/transcripts")
    public ApiResponse<List<Map<String, Object>>> getTranscripts(@PathVariable String id) {
        Meeting m = meetingRepo.selectById(id);
        if (m == null || !cur.storeId().equals(m.getStoreId())) {
            throw BizException.notFound("会谈记录");
        }
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
}
