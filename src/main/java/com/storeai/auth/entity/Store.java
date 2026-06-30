package com.storeai.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("stores")
public class Store {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    private String ownerId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
