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
    /** normal / high / urgent；用于工作台排序与风险提醒。 */
    private String priority;
    private String assignedTo;
    private String createdBy;
    /** 任务所服务的客户；用于任务反馈、客户时间线和可追溯筛选。 */
    private String customerId;
    private OffsetDateTime dueAt;
    private String feedback;
    /** 自动生成任务的来源，例如 meeting_analysis / manual_meeting_candidate。 */
    private String sourceType;
    /** 来源分析、候选或其它业务记录 ID。 */
    private String sourceId;
    /** 关联的原始会谈，便于审核人回看逐字稿和录音。 */
    private String sourceMeetingId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
