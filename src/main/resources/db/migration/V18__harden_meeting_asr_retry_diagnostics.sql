-- 可恢复的语音转写：保留安全的失败类别和下一次自动重试时间，避免用笼统的“处理失败”覆盖真实状态。
ALTER TABLE meetings
    ADD COLUMN asr_retry_at DATETIME DEFAULT NULL,
    ADD COLUMN asr_error_code VARCHAR(64) DEFAULT NULL;

CREATE INDEX idx_meetings_asr_retry ON meetings (status, asr_retry_at);
