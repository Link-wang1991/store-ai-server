-- 会谈转写的可恢复处理状态，以及原始逐句转写的修订留痕。
-- content 始终是当前用于复盘的版本；original_content 保留第三方 ASR 返回的原文。

ALTER TABLE meetings
    ADD COLUMN asr_poll_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN asr_last_polled_at DATETIME DEFAULT NULL;

ALTER TABLE meeting_transcripts
    ADD COLUMN original_content TEXT DEFAULT NULL,
    ADD COLUMN edited_by VARCHAR(64) DEFAULT NULL,
    ADD COLUMN edited_at DATETIME DEFAULT NULL;

UPDATE meeting_transcripts
SET original_content = content
WHERE original_content IS NULL;
