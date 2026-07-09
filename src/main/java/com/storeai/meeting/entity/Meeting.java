package com.storeai.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("meetings")
public class Meeting {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String employeeId;
    private String customerId;
    private String scene;
    private String status;
    private String employeeName;
    private String customerName;
    private Integer duration;
    private String audioUrl;
    private String asrTaskId;
    private String transcriptStatus;
    private String analysisStatus;
    private Integer audioDuration;
    private OffsetDateTime endedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
