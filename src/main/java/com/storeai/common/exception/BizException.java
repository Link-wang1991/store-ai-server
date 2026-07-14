package com.storeai.common.exception;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        super(message);
        this.code = 500;
    }

    public static BizException unauthorized() {
        return new BizException(401, "未登录或登录已过期");
    }

    public static BizException forbidden() {
        return new BizException(403, "无权限执行此操作");
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    public static BizException notFound(String resource) {
        return new BizException(404, resource + "不存在");
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }
}
