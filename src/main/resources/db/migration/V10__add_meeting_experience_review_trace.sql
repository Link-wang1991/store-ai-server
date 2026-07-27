-- 会谈经验从“候选”到“正式知识”的审核追溯。
-- 任务保留来源会谈与分析，正式知识保留来源和审核人，避免未经审核的内容混入知识库。

ALTER TABLE tasks
    ADD COLUMN source_type VARCHAR(50) DEFAULT NULL,
    ADD COLUMN source_id VARCHAR(64) DEFAULT NULL,
    ADD COLUMN source_meeting_id VARCHAR(64) DEFAULT NULL,
    ADD INDEX idx_tasks_experience_review (store_id, type, status),
    ADD INDEX idx_tasks_source_meeting (source_meeting_id);

ALTER TABLE knowledge_documents
    ADD COLUMN source_type VARCHAR(50) DEFAULT NULL,
    ADD COLUMN source_id VARCHAR(64) DEFAULT NULL,
    ADD COLUMN source_meeting_id VARCHAR(64) DEFAULT NULL,
    ADD COLUMN reviewed_by VARCHAR(64) DEFAULT NULL,
    ADD COLUMN reviewed_at DATETIME DEFAULT NULL,
    ADD INDEX idx_knowledge_source_meeting (source_meeting_id);
