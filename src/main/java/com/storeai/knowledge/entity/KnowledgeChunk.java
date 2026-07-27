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
    /** JSON 序列化的向量；不可用时为空并自动降级关键词检索。 */
    private String embedding;
    private String embeddingModel;
    private int seq;
    private OffsetDateTime createdAt;
}
