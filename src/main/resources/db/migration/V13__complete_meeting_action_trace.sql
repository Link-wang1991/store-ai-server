-- 会谈闭环补齐：任务能精确关联客户/会谈/分析；转写修订后的行动建议必须经过人工确认。

ALTER TABLE tasks
    ADD COLUMN customer_id VARCHAR(64) DEFAULT NULL,
    ADD INDEX idx_tasks_customer_status (customer_id, status);

ALTER TABLE meetings
    ADD COLUMN action_review_status VARCHAR(32) NOT NULL DEFAULT 'not_required',
    ADD INDEX idx_meetings_action_review (store_id, action_review_status);
