-- AI 教练不直接写业务动作：先保存员工可核对的提案，确认后才创建正式任务。

CREATE TABLE ai_action_proposals (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    employee_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) DEFAULT NULL,
    action_type VARCHAR(32) NOT NULL DEFAULT 'followup',
    title VARCHAR(200) NOT NULL,
    content TEXT,
    due_at DATETIME DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    applied_task_id VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uq_ai_action_proposal_message (message_id),
    INDEX idx_ai_action_proposals_employee (store_id, employee_id, status),
    INDEX idx_ai_action_proposals_customer (customer_id, status)
);
