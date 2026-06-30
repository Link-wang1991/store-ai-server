package com.storeai.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@TableName("reports")
public class Report {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String type;
    private String content;      // JSONB
    private LocalDate reportDate;
    private OffsetDateTime createdAt;
}
