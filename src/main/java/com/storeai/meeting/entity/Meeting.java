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
    private String status;       // recording / transcribing / analyzing / done / failed
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
