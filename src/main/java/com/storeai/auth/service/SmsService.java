package com.storeai.auth.service;

/**
 * 短信发送抽象层。
 * 开发期默认使用 {@link MockSmsService}（仅打印验证码到日志，并可在响应中回传方便联调）；
 * 生产接入阿里云/腾讯云时实现 {@link AliyunSmsService} 并通过 {@code app.sms.mode=aliyun} 切换。
 */
public interface SmsService {

    /**
     * 发送验证码。
     *
     * @param phone 手机号
     * @param code  6 位验证码
     * @param type  业务类型：login / reset_password
     */
    void sendCode(String phone, String code, String type);
}
