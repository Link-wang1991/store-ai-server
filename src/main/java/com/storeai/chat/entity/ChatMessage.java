package com.storeai.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("chat_messages")
public class ChatMessage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String sessionId;
    private String employeeId;
    private String role;            // user / ai
    private String content;
    private String answerType;      // knowledge / general / need_confirm / risk
    private String riskLevel;       // L1 / L2 / L3 / L4
    private String retrievedChunks; // JSON
    private String customerId;
    private OffsetDateTime createdAt;
}
