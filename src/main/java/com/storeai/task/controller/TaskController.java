package com.storeai.task.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.common.util.CurrentUser;
import com.storeai.common.exception.BizException;
import com.storeai.task.entity.Task;
import com.storeai.task.repository.TaskRepository;
import com.storeai.task.service.TaskFeedbackService;
import com.storeai.task.service.TaskTraceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
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
    private final CurrentUser cur;

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

    public record CompleteRequest(String outcome, String note) {}
}
