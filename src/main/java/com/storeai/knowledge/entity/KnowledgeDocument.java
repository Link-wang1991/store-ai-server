package com.storeai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("knowledge_documents")
public class KnowledgeDocument {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String title;
    private String category;
    private String status;           // active / inactive
    private String uploadedBy;       // employee_id
    private String visibleRoles;     // JSON array: ["owner","manager","consultant"]
    private String tags;
    private String remark;
    private String fileUrl;
    private String fileType;
    /** 内容来源，审核沉淀的内容可回溯到原会谈和分析。 */
    private String sourceType;
    private String sourceId;
    private String sourceMeetingId;
    private String reviewedBy;
    private OffsetDateTime reviewedAt;
    /** draft / approved / needs_review / retired；只有 approved 且在有效期内的资料参与检索。 */
    private String reviewStatus;
    private OffsetDateTime effectiveAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime reviewDueAt;
    private OffsetDateTime lastReviewedAt;
    private String lastReviewedBy;
    private String versionLabel;
    private String reviewNote;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
