package com.storeai.task.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.common.util.CurrentUser;
import com.storeai.common.exception.BizException;
import com.storeai.task.entity.Task;
import com.storeai.task.repository.TaskRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "任务管理")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskRepository taskRepo;
    private final CurrentUser cur;

    @GetMapping
    public ApiResponse<List<Task>> list(@RequestParam(required = false) String status) {
        var qw = new LambdaQueryWrapper<Task>()
                .eq(Task::getStoreId, cur.storeId());
        if (!cur.isAdmin()) {
            qw.eq(Task::getAssignedTo, cur.employeeId());
        }
        if (status != null) {
            qw.eq(Task::getStatus, status);
        }
        qw.orderByDesc(Task::getCreatedAt);
        return ApiResponse.ok(taskRepo.selectList(qw));
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
        t.setStatus(status);
        t.setUpdatedAt(OffsetDateTime.now());
        taskRepo.updateById(t);
        return ApiResponse.ok(t);
    }
}
