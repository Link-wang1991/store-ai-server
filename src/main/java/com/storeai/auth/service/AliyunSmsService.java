package com.storeai.auth.service;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 阿里云短信实现（真实下发）。
 *
 * <p>启用方式：在 application.yml（或环境变量）配置 {@code app.sms.mode=aliyun}，并填写：
 * <pre>
 * app:
 *   sms:
 *     mode: aliyun
 *     aliyun:
 *       access-key-id: ${ALIYUN_SMS_ACCESS_KEY_ID:}
 *       access-key-secret: ${ALIYUN_SMS_ACCESS_KEY_SECRET:}
 *       sign-name: ${ALIYUN_SMS_SIGN_NAME:}          # 短信签名，如「门店AI助手」
 *       endpoint: ${ALIYUN_SMS_ENDPOINT:dysmsapi.aliyuncs.com}
 *       # 模板可分别配置，也可只配通用 template-code：
 *       template-code: ${ALIYUN_SMS_TEMPLATE_CODE:}               # 通用模板（变量必须为 ${code}）
 *       template-code-login: ${ALIYUN_SMS_TEMPLATE_LOGIN:}        # 登录验证码模板（可选，优先）
 *       template-code-reset-password: ${ALIYUN_SMS_TEMPLATE_RESET:} # 重置密码模板（可选，优先）
 * </pre>
 * 未配置凭证或签名时启动不失败，但发送验证码会抛出明确异常，提示先补充配置。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "aliyun")
public class AliyunSmsService implements SmsService {

    @Value("${app.sms.aliyun.access-key-id:}")
    private String accessKeyId;

    @Value("${app.sms.aliyun.access-key-secret:}")
    private String accessKeySecret;

    @Value("${app.sms.aliyun.sign-name:}")
    private String signName;

    @Value("${app.sms.aliyun.endpoint:dysmsapi.aliyuncs.com}")
    private String endpoint;

    /** 通用模板（变量为 ${code}）。 */
    @Value("${app.sms.aliyun.template-code:}")
    private String templateCode;

    /** 登录验证码模板（可选，优先于通用模板）。 */
    @Value("${app.sms.aliyun.template-code-login:}")
    private String templateCodeLogin;

    /** 重置密码验证码模板（可选，优先于通用模板）。 */
    @Value("${app.sms.aliyun.template-code-reset-password:}")
    private String templateCodeResetPassword;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Override
    public void sendCode(String phone, String code, String type) {
        String template = resolveTemplate(type);
        if (template == null || template.isBlank()) {
            throw new BizException("短信模板未配置：app.sms.aliyun.template-code（或按 type 的模板）。请先在配置文件中填写");
        }
        if (isBlank(accessKeyId) || isBlank(accessKeySecret) || isBlank(signName)) {
            throw new BizException("阿里云短信未配置完整：app.sms.aliyun.access-key-id / access-key-secret / sign-name 必填");
        }

        try {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint(endpoint);
            Client client = new Client(config);

            String templateParam = jsonMapper.writeValueAsString(Map.of("code", code));
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(signName)
                    .setTemplateCode(template)
                    .setTemplateParam(templateParam);
            SendSmsResponse response = client.sendSms(request);

            String responseCode = response.getBody() != null ? response.getBody().getCode() : null;
            if ("OK".equals(responseCode)) {
                log.info("[阿里云短信] 发送成功 phone={} type={} code={} response={}",
                        phone, type, code, response.getBody().getMessage());
            } else {
                String message = response.getBody() != null ? response.getBody().getMessage() : "未知错误";
                log.error("[阿里云短信] 发送失败 phone={} type={} code={} code={} message={}",
                        phone, type, code, responseCode, message);
                throw new BizException("短信发送失败：" + message);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[阿里云短信] 发送异常 phone={} type={}", phone, type, e);
            throw new BizException("短信发送异常：" + e.getMessage());
        }
    }

    /** 按业务类型选择模板；未单独配置时回退到通用 template-code。 */
    private String resolveTemplate(String type) {
        if ("reset_password".equals(type)) {
            return firstNotBlank(templateCodeResetPassword, templateCode);
        }
        if ("login".equals(type)) {
            return firstNotBlank(templateCodeLogin, templateCode);
        }
        return templateCode;
    }

    private String firstNotBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
