package com.storeai.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RegisterResponse {
    private String token;
    private String userId;
    private String employeeId;
    private String storeId;
    private String role;
    private String roleLabel;
    private String storeName;
    private String name;
}
