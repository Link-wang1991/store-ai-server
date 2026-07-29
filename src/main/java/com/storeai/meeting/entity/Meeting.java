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
    private Integer asrSubmitAttempts;
    private OffsetDateTime asrSubmitStartedAt;
    private Integer asrPollFailures;
    private OffsetDateTime asrLastPolledAt;
    private OffsetDateTime asrRetryAt;
    private String asrErrorCode;
    private String transcriptStatus;
    private String failReason;
    private String analysisStatus;
    /** 人工修订转写后，新的跟进行动是否仍待员工确认。 */
    private String actionReviewStatus;
    /** 会谈分析后的任务/记忆/审核闭环状态：pending / processing / completed / partial_failed。 */
    private String closureStatus;
    private Integer closureAttempts;
    private String closureError;
    private Integer qualityScore;
    private Integer audioDuration;
    private OffsetDateTime endedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
