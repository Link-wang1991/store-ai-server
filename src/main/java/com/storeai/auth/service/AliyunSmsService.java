package com.storeai.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 阿里云短信实现桩。
 *
 * 接入方式：在 application.yml（或环境变量）配置 {@code app.sms.mode=aliyun}，
 * 并通过 {@code app.sms.aliyun.*} 提供 AccessKey、签名、模板。当前为占位实现，
 * 避免未配置凭证时启动失败；真实下发逻辑在此补充。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "aliyun")
public class AliyunSmsService implements SmsService {

    @Override
    public void sendCode(String phone, String code, String type) {
        // TODO: 接入阿里云短信 SendSms，按 type 选择模板（login / reset_password）。
        log.info("[阿里云短信] 待接入：phone={} type={} code={}", phone, type, code);
        throw new UnsupportedOperationException("阿里云短信尚未接入，请配置 app.sms.mode=mock 或实现 AliyunSmsService");
    }
}
