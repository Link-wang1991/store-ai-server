package com.storeai.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 开发期默认实现：不真正下发短信，仅把验证码打印到日志。
 * 配合 {@code app.sms.mode=mock}（默认），登录接口会在响应里回传 devCode 方便联调。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "mock", matchIfMissing = true)
public class MockSmsService implements SmsService {

    @Override
    public void sendCode(String phone, String code, String type) {
        log.warn("[MOCK 短信] phone={} type={} code={}（开发环境未真实下发）", phone, type, code);
    }
}
