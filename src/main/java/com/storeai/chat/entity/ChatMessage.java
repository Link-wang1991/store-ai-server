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
    private String aiResponse;
    private String questionCategory;
    private String answerType;      // knowledge / general / need_confirm / risk
    /** model / fallback / safety_rule，避免把降级模板误呈现为模型结论。 */
    private String generationMode;
    /** 客户端重试标识：同一员工的同一次请求只能持久化一条问答。 */
    private String clientRequestId;
    private String riskLevel;       // L1 / L2 / L3 / L4
    private String retrievedChunks; // JSON
    private String methodologySources; // JSON，系统销售方法论来源
    private Boolean needsReview;
    private String customerId;
    private OffsetDateTime createdAt;
}
