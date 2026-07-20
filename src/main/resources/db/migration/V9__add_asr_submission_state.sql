-- Persist retry state so a slow or interrupted ASR submission cannot leave a meeting stuck forever.
ALTER TABLE meetings
    ADD COLUMN asr_submit_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN asr_submit_started_at DATETIME DEFAULT NULL;
