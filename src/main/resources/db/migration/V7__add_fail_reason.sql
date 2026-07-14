-- V5: add fail_reason column
ALTER TABLE meetings
    ADD COLUMN fail_reason VARCHAR(255) DEFAULT NULL
    AFTER transcript_status;
