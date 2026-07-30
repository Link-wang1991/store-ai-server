-- 会谈分析失败必须可见、可限次重试，不能将空报告伪装成已完成。
ALTER TABLE meetings
    ADD COLUMN analysis_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN analysis_retry_at DATETIME NULL,
    ADD COLUMN analysis_error_code VARCHAR(64) NULL;

CREATE INDEX idx_meetings_analysis_retry
    ON meetings (status, analysis_status, analysis_retry_at);

-- 区分真实模型生成、确定性安全规则和资料兜底，历史聊天刷新后也能追溯。
ALTER TABLE chat_messages
    ADD COLUMN generation_mode VARCHAR(24) NULL,
    ADD COLUMN client_request_id VARCHAR(80) NULL;

CREATE UNIQUE INDEX uq_chat_messages_client_request
    ON chat_messages (store_id, employee_id, client_request_id);

-- 自动评分与人工复核分开保存：人工复核用于校准和培训，不得覆盖自动合规结论。
ALTER TABLE meeting_analysis
    ADD COLUMN quality_review_status VARCHAR(20) NULL,
    ADD COLUMN quality_review_score INT NULL,
    ADD COLUMN quality_review_note TEXT NULL,
    ADD COLUMN quality_reviewed_by VARCHAR(64) NULL,
    ADD COLUMN quality_reviewed_at DATETIME NULL;
