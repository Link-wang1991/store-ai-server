package com.storeai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    /** 登录邮箱（与手机号二选一，手机号优先）。 */
    private String email;

    /** 登录手机号（唯一标识）。密码登录或验证码登录都以此为准。 */
    private String phone;

    @NotBlank(message = "密码不能为空")
    private String password;
}
