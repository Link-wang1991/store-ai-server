-- ============================================================
-- V5: 补全 meeting_transcripts / meeting_analysis 字段
-- 前端写入时需要 store_id 隔离
-- ============================================================

ALTER TABLE meeting_transcripts
    ADD COLUMN store_id VARCHAR(64) DEFAULT NULL,
    ADD COLUMN speaker_role VARCHAR(50) DEFAULT NULL,
    ADD COLUMN start_time DECIMAL(10,2) DEFAULT NULL,
    ADD COLUMN end_time DECIMAL(10,2) DEFAULT NULL,
    ADD COLUMN confidence DECIMAL(5,3) DEFAULT NULL,
    ADD INDEX idx_mtrans_meeting_seq (meeting_id, seq);

ALTER TABLE meeting_analysis
    ADD COLUMN store_id VARCHAR(64) DEFAULT NULL,
    ADD COLUMN customer_id VARCHAR(64) DEFAULT NULL,
    ADD COLUMN employee_id VARCHAR(64) DEFAULT NULL,
    ADD COLUMN summary TEXT DEFAULT NULL,
    ADD COLUMN key_points TEXT DEFAULT NULL,
    ADD COLUMN explicit_needs TEXT DEFAULT NULL,
    ADD COLUMN implicit_needs TEXT DEFAULT NULL,
    ADD COLUMN emotional_needs TEXT DEFAULT NULL,
    ADD COLUMN decision_barriers TEXT DEFAULT NULL,
    ADD COLUMN customer_personality TEXT DEFAULT NULL,
    ADD COLUMN customer_comm_pref TEXT DEFAULT NULL,
    ADD COLUMN customer_spending_power TEXT DEFAULT NULL,
    ADD COLUMN employee_did_well TEXT DEFAULT NULL,
    ADD COLUMN employee_to_improve TEXT DEFAULT NULL,
    ADD COLUMN missed_opportunities TEXT DEFAULT NULL,
    ADD COLUMN service_experience_risk TEXT DEFAULT NULL,
    ADD COLUMN compliance_risks TEXT DEFAULT NULL,
    ADD COLUMN followup_goal TEXT DEFAULT NULL,
    ADD COLUMN suggested_followup_at DATETIME DEFAULT NULL,
    ADD COLUMN suggested_script TEXT DEFAULT NULL,
    ADD COLUMN need_manager_involved TINYINT(1) DEFAULT 0,
    ADD COLUMN analysis_json JSON DEFAULT NULL,
    ADD INDEX idx_manalysis_meeting (meeting_id);
