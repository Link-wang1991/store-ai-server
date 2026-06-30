package com.storeai.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("tasks")
public class Task {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String title;
    private String content;
    private String type;
    private String status;       // todo / doing / done / overdue / canceled
    private String assignedTo;
    private String createdBy;
    private OffsetDateTime dueAt;
    private String feedback;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
