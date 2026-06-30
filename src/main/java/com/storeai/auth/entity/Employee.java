package com.storeai.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("employees")
public class Employee {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String userId;
    private String name;
    private String role;        // owner / manager / consultant / beautician / receptionist / operator
    private String status;       // active / inactive
    private String phone;
    private String dataScope;    // self / store
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
