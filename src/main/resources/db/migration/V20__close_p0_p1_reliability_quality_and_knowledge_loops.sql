-- P0：录音上传与转写链路必须可诊断。音频二进制仍保存在受控存储中，数据库只记录状态与元数据。
ALTER TABLE meetings
    ADD COLUMN audio_upload_state VARCHAR(24) NOT NULL DEFAULT 'pending',
    ADD COLUMN audio_bytes BIGINT NULL,
    ADD COLUMN audio_mime_type VARCHAR(120) NULL,
    ADD COLUMN audio_received_at DATETIME NULL;

CREATE INDEX idx_meetings_audio_state ON meetings (store_id, audio_upload_state, updated_at);

-- P1：人工复核保留“为什么调整”的结构化理由，才可以统计自动评分与人工判断的偏差。
ALTER TABLE meeting_analysis
    ADD COLUMN quality_review_reason_codes JSON NULL;

-- P1：任务的“做完动作”与“取得业务结果”分离，避免把点击完成误计为成交或风险闭环。
ALTER TABLE tasks
    ADD COLUMN business_outcome_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    ADD COLUMN result_code VARCHAR(50) NULL,
    ADD COLUMN result_detail TEXT NULL,
    ADD COLUMN result_recorded_at DATETIME NULL,
    ADD COLUMN result_verified_at DATETIME NULL,
    ADD COLUMN result_verified_by VARCHAR(64) NULL,
    ADD COLUMN next_follow_at DATETIME NULL,
    ADD COLUMN requires_result_verification TINYINT(1) NOT NULL DEFAULT 0;

CREATE INDEX idx_tasks_business_outcome ON tasks (store_id, business_outcome_status, status);

-- P1：门店知识进入正式检索前有状态、版本、复核周期和失效日期；到期资料不再参与检索。
ALTER TABLE knowledge_documents
    ADD COLUMN review_status VARCHAR(24) NOT NULL DEFAULT 'approved',
    ADD COLUMN effective_at DATETIME NULL,
    ADD COLUMN expires_at DATETIME NULL,
    ADD COLUMN review_due_at DATETIME NULL,
    ADD COLUMN last_reviewed_at DATETIME NULL,
    ADD COLUMN last_reviewed_by VARCHAR(64) NULL,
    ADD COLUMN version_label VARCHAR(64) NULL,
    ADD COLUMN review_note TEXT NULL;

CREATE INDEX idx_kd_lifecycle ON knowledge_documents (store_id, status, review_status, expires_at, review_due_at);

-- P1：检索质量不使用演示数据虚构命中率。每一条指标都来自店长实际录入的测试题与判定。
CREATE TABLE knowledge_retrieval_evaluations (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    question TEXT NOT NULL,
    expected_document_id VARCHAR(64) NULL,
    returned_document_ids JSON NULL,
    top_score DECIMAL(10,4) NULL,
    evaluation_status VARCHAR(24) NOT NULL DEFAULT 'unrated',
    note TEXT NULL,
    created_by VARCHAR(64) NULL,
    reviewed_by VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at DATETIME NULL,
    INDEX idx_knowledge_eval_store_created (store_id, created_at),
    INDEX idx_knowledge_eval_status (store_id, evaluation_status)
);

-- P1：本机免密角色体验可审计，便于追查谁在何时签发了哪一种短时体验身份。
CREATE TABLE role_preview_audit (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NULL,
    target_employee_id VARCHAR(64) NULL,
    action VARCHAR(32) NOT NULL,
    request_ip VARCHAR(80) NULL,
    request_origin VARCHAR(255) NULL,
    user_agent VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_role_preview_audit_store_created (store_id, created_at)
);
