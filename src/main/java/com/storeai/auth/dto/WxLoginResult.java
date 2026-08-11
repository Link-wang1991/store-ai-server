package com.storeai.auth.dto;

/**
 * 微信登录结果：
 * - needBind=false 且 token 非空：已绑定，直接登录；
 * - needBind=true：该微信未绑定门店账号，前端引导用户填写手机号+验证码完成绑定。
 */
public record WxLoginResult(
        boolean needBind,
        String token,
        String userId,
        String employeeId,
        String storeId,
        String role,
        String roleLabel,
        String storeName,
        String name
) {
}
