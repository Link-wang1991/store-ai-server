package com.storeai.auth.dto;

/**
 * 微信一键登录请求：携带 wx.login() 返回的临时 code。
 */
public record WxLoginRequest(String code) {
}
