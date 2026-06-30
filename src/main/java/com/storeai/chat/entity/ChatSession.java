package com.storeai.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("chat_sessions")
public class ChatSession {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String employeeId;
    private String role;
    private String title;
    private String customerId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
