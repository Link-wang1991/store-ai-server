package com.storeai.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("users")
public class User {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String authUserId;  // 关联 Supabase Auth uuid
    private String email;
    private String name;
    private String passwordHash;
    private OffsetDateTime createdAt;
}
