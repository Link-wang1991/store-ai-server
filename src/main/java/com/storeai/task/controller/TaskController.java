package com.storeai.task.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.common.util.CurrentUser;
import com.storeai.common.exception.BizException;
import com.storeai.task.entity.Task;
import com.storeai.task.repository.TaskRepository;
import com.storeai.task.service.TaskAttachmentService;
import com.storeai.task.service.TaskFeedbackService;
import com.storeai.task.service.TaskTraceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "任务管理")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskRepository taskRepo;
    private final TaskFeedbackService taskFeedbackService;
    private final TaskTraceService taskTraceService;
    private final TaskAttachmentService taskAttachmentService;
    private final CurrentUser cur;
    private final JdbcTemplate jdbc;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        // 与首页共用来源装配：任务不只是一条文本，还必须能回到客户、会谈、AI 对话
        // 和当时引用的资料快照。
        return ApiResponse.ok(taskTraceService.listForCurrentEmployee(status));
    }

    @PostMapping
    public ApiResponse<Task> create(@RequestBody Task task) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        task.setStoreId(cur.storeId());
        task.setCreatedBy(cur.employeeId());
        task.setStatus("todo");
        task.setCreatedAt(OffsetDateTime.now());
        task.setUpdatedAt(OffsetDateTime.now());
        taskRepo.insert(task);
        return ApiResponse.ok(task);
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Task> updateStatus(@PathVariable String id,
                                           @RequestParam String status) {
        Task t = taskRepo.selectById(id);
        if (t == null || !cur.storeId().equals(t.getStoreId())) {
            throw BizException.notFound("任务");
        }
        if (!cur.isAdmin() && !cur.employeeId().equals(t.getAssignedTo())) {
            throw BizException.forbidden("只有任务负责人可以更新任务状态");
        }
        if (!List.of("todo", "doing", "canceled").contains(status)) {
            throw BizException.badRequest("任务状态只能更新为 todo、doing 或 canceled；完成请提交任务结果");
        }
        t.setStatus(status);
        t.setUpdatedAt(OffsetDateTime.now());
        taskRepo.updateById(t);
        return ApiResponse.ok(t);
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<Map<String, Object>> complete(@PathVariable String id,
                                                      @RequestBody CompleteRequest req) {
        return ApiResponse.ok(taskFeedbackService.complete(id, req.outcome(), req.note()));
    }

    /** 任务延期：把截止时间改到 new_due_at（ISO 或 YYYY-MM-DD HH:mm）。仅负责人或管理员。 */
    @PostMapping("/{id}/defer")
    public ApiResponse<Map<String, Object>> defer(@PathVariable String id,
                                                   @RequestBody DeferRequest req) {
        if (req.newDueAt() == null || req.newDueAt().isBlank()) {
            throw BizException.badRequest("请设置新的截止时间");
        }
        requireTaskOwner(id);
        OffsetDateTime newDue;
        try {
            newDue = OffsetDateTime.parse(req.newDueAt().trim());
        } catch (Exception e) {
            try {
                newDue = java.time.LocalDateTime.parse(req.newDueAt().trim())
                    .atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
            } catch (Exception e2) {
                throw BizException.badRequest("截止时间格式不正确，请使用 ISO 时间或 YYYY-MM-DD HH:mm");
            }
        }
        // 延期后任务回到"待处理"（todo），方便员工在调整后的时间重新安排执行
        int n = jdbc.update("""
            UPDATE tasks
            SET due_at = ?, status = CASE WHEN status IN ('todo', 'doing') THEN status ELSE 'todo' END,
                updated_at = NOW()
            WHERE id = ? AND store_id = ?
            """, newDue, id, cur.storeId());
        if (n == 0) throw BizException.notFound("任务");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", id);
        result.put("due_at", newDue.toString());
        result.put("message", "任务已延期");
        return ApiResponse.ok(result);
    }

    /** 任务指派 / 转交：把任务转给指定员工。仅任务当前负责人或管理员可操作。 */
    @PostMapping("/{id}/assign")
    public ApiResponse<Map<String, Object>> assign(@PathVariable String id,
                                                    @RequestBody AssignRequest req) {
        String target = req.assignedTo();
        if (target == null || target.isBlank()) {
            throw BizException.badRequest("请选择负责人");
        }
        requireTaskOwner(id);
        // 校验目标员工存在且属于本店
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM employees WHERE id = ? AND store_id = ? AND status = 'active'
            """, Integer.class, target, cur.storeId());
        if (count == null || count == 0) {
            throw BizException.badRequest("目标负责人不存在或已停用");
        }
        int n = jdbc.update("""
            UPDATE tasks
            SET assigned_to = ?, updated_at = NOW()
            WHERE id = ? AND store_id = ?
            """, target, id, cur.storeId());
        if (n == 0) throw BizException.notFound("任务");
        String targetName = jdbc.queryForObject(
            "SELECT name FROM employees WHERE id = ? LIMIT 1", String.class, target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", id);
        result.put("assigned_to", target);
        result.put("assigned_to_name", targetName);
        result.put("message", "任务已转交");
        return ApiResponse.ok(result);
    }

    /** 可指派的员工候选（管理员看全店员工；普通员工只能选自己）。 */
    @GetMapping("/assignees")
    public ApiResponse<List<Map<String, Object>>> assignees() {
        if (cur.isAdmin()) {
            return ApiResponse.ok(jdbc.queryForList(
                "SELECT id, name, role FROM employees WHERE store_id = ? AND status = 'active' ORDER BY name ASC",
                cur.storeId()));
        }
        return ApiResponse.ok(jdbc.queryForList(
            "SELECT id, name, role FROM employees WHERE id = ? AND store_id = ? AND status = 'active'",
            cur.employeeId(), cur.storeId()));
    }

    // ===== 证据附件 =====

    /** 上传任务证据附件（multipart file）。 */
    @PostMapping("/{id}/attachments")
    public ApiResponse<Map<String, Object>> uploadAttachment(@PathVariable String id,
                                                              @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(taskAttachmentService.upload(id, file));
    }

    /** 列出任务附件元数据。 */
    @GetMapping("/{id}/attachments")
    public ApiResponse<List<Map<String, Object>>> listAttachments(@PathVariable String id) {
        return ApiResponse.ok(taskAttachmentService.list(id));
    }

    /** 下载任务附件（受登录保护，local 返回文件流，minio 返回私有桶流）。 */
    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<?> downloadAttachment(@PathVariable String id,
                                                 @PathVariable String attachmentId) {
        try {
            if (taskAttachmentService.isLocalAttachment(id, attachmentId)) {
                Path path = taskAttachmentService.openLocal(id, attachmentId);
                String name = taskAttachmentService.originalName(id, attachmentId);
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new FileSystemResource(path.toFile()));
            }
            InputStream in = taskAttachmentService.openMinio(id, attachmentId);
            String name = taskAttachmentService.originalName(id, attachmentId);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(in));
        } catch (Exception e) {
            throw new BizException("附件读取失败");
        }
    }

    /** 删除任务附件。 */
    @DeleteMapping("/{id}/attachments/{attachmentId}")
    public ApiResponse<Void> deleteAttachment(@PathVariable String id,
                                               @PathVariable String attachmentId) {
        taskAttachmentService.delete(id, attachmentId);
        return ApiResponse.ok();
    }

    /** 校验当前用户是该任务负责人或管理员。 */
    private void requireTaskOwner(String id) {
        Task t = taskRepo.selectById(id);
        if (t == null || !cur.storeId().equals(t.getStoreId())) {
            throw BizException.notFound("任务");
        }
        if (!cur.isAdmin() && !cur.employeeId().equals(t.getAssignedTo())) {
            throw BizException.forbidden("只有任务负责人可以操作该任务");
        }
    }

    public record CompleteRequest(String outcome, String note) {}
    public record DeferRequest(String newDueAt) {}
    public record AssignRequest(String assignedTo) {}
}
