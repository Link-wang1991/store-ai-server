-- AI 教练回答的采纳结果。一个员工对一条回答保留一个当前反馈，便于后续复盘与统计。
CREATE TABLE IF NOT EXISTS ai_feedback (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    employee_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) DEFAULT NULL,
    feedback_type VARCHAR(32) NOT NULL,
    is_helpful TINYINT(1) NOT NULL DEFAULT 0,
    comment TEXT DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_feedback_employee_message (employee_id, message_id),
    INDEX idx_ai_feedback_store (store_id),
    INDEX idx_ai_feedback_message (message_id)
);
