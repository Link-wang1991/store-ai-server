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
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
