-- ============================================================
-- V4: 补充会议表字段（会谈转写需要）
-- ============================================================

ALTER TABLE meetings
    ADD COLUMN audio_url TEXT DEFAULT NULL,
    ADD COLUMN asr_task_id VARCHAR(128) DEFAULT NULL,
    ADD COLUMN transcript_status VARCHAR(20) DEFAULT 'pending',
    ADD COLUMN analysis_status VARCHAR(20) DEFAULT 'pending',
    ADD COLUMN duration INT DEFAULT NULL,
    ADD COLUMN ended_at DATETIME DEFAULT NULL,
    ADD COLUMN audio_duration INT DEFAULT NULL;
