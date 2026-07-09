-- ============================================================
-- V6: 补全角色定义与权限表（被 auth 模块引用，用于前端权限查询）
-- ============================================================

CREATE TABLE IF NOT EXISTS role_definitions (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    role_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    base_role VARCHAR(50) NOT NULL DEFAULT 'consultant',
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    sort_order INT NOT NULL DEFAULT 0,
    description TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_def (store_id, role_key)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    role_key VARCHAR(100) NOT NULL,
    module VARCHAR(50) NOT NULL,
    actions JSON DEFAULT NULL,
    data_scope VARCHAR(20) NOT NULL DEFAULT 'self',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_perm (store_id, role_key, module)
);

-- 为演示门店插入默认角色定义（和角色权限会需要配套插入）
-- 演示门店 store-demo-001 已有 store_id
INSERT IGNORE INTO role_definitions (id, store_id, role_key, display_name, base_role, sort_order)
SELECT
    CONCAT('rd-', s.id, '-owner'), s.id, 'owner', '老板', 'owner', 1
FROM stores s WHERE s.id = 'store-demo-001'
UNION ALL
SELECT CONCAT('rd-', s.id, '-manager'), s.id, 'manager', '店长', 'manager', 2
FROM stores s WHERE s.id = 'store-demo-001'
UNION ALL
SELECT CONCAT('rd-', s.id, '-consultant'), s.id, 'consultant', '咨询顾问', 'consultant', 3
FROM stores s WHERE s.id = 'store-demo-001'
UNION ALL
SELECT CONCAT('rd-', s.id, '-beautician'), s.id, 'beautician', '美容师', 'beautician', 4
FROM stores s WHERE s.id = 'store-demo-001'
UNION ALL
SELECT CONCAT('rd-', s.id, '-receptionist'), s.id, 'receptionist', '前台', 'receptionist', 5
FROM stores s WHERE s.id = 'store-demo-001';
