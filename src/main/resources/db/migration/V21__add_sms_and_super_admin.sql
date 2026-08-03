-- 验证码表（手机号登录 / 找回密码等场景复用）
CREATE TABLE IF NOT EXISTS sms_verification_codes (
    id          VARCHAR(64)  PRIMARY KEY,
    phone       VARCHAR(30)  NOT NULL,
    code        VARCHAR(10)  NOT NULL,
    type        VARCHAR(20)  NOT NULL COMMENT 'login / reset_password',
    expires_at  DATETIME     NOT NULL,
    attempts    INT          DEFAULT 0,
    used        TINYINT(1)   DEFAULT 0,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sms_phone (phone),
    INDEX idx_sms_phone_type (phone, type)
) COMMENT='短信验证码';

-- 超级管理员角色标签表（平台级，不绑定具体门店）
CREATE TABLE IF NOT EXISTS platform_roles (
    id          VARCHAR(64)  PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE COMMENT 'super_admin',
    name        VARCHAR(100) NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
) COMMENT='平台角色';

INSERT IGNORE INTO platform_roles (id, code, name) VALUES ('platform_role_super_admin', 'super_admin', '超级管理员');
