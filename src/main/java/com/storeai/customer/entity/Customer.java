package com.storeai.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("customers")
public class Customer {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String storeId;
    private String name;
    private String phone;
    private String gender;
    private Integer age;
    private String assignedTo;      // employee_id
    private String stage;           // new / intent / deal / regular / churn_risk
    private String pool;            // today / new / new_deal / regular / dormant / risk
    private String tags;
    private String portrait;        // AI 画像 JSON
    private Integer totalVisits;
    private String concerns;         // 客户顾虑/异议（JSON 数组或文本）
    private String aiSuggestion;     // AI 跟进建议
    private String importRaw;        // 导入原始数据 JSON（含 insight）
    private OffsetDateTime lastActiveAt; // 上次互动时间
    private OffsetDateTime lastVisitAt;
    private OffsetDateTime nextFollowAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
