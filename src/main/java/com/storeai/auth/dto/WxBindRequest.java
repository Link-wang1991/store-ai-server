package com.storeai.auth.dto;

/**
 * 微信登录后绑定手机号：code 用于换取 openid，phone + smsCode 复用短信验证码校验。
 */
public record WxBindRequest(String code, String phone, String smsCode) {
}
