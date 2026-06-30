package com.storeai.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("interactions")
public class Interaction {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String customerId;
    private String employeeId;
    private String type;
    private String content;
    private OffsetDateTime createdAt;
}
