package com.storeai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("knowledge_chunks")
public class KnowledgeChunk {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String documentId;
    private String content;
    private int seq;
    private OffsetDateTime createdAt;
}
