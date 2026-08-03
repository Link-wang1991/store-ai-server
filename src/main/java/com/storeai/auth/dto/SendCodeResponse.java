package com.storeai.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SendCodeResponse {
    /** 发送成功 */
    private boolean sent;
    /** 开发期 mock 模式回传的验证码，方便联调；生产/真实短信不下发此字段 */
    private String devCode;
    /** 下次可重发间隔（秒） */
    private int retryAfterSeconds;
}
