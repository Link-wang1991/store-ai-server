-- 会谈 / AI 教练核心闭环加固：闭环执行状态、待确认记忆、可编辑 AI 待办、语义检索缓存。

ALTER TABLE meetings
    ADD COLUMN closure_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    ADD COLUMN closure_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN closure_error TEXT DEFAULT NULL,
    ADD INDEX idx_meetings_closure_status (store_id, closure_status);

-- 迁移前已完成的历史会谈没有可重放的闭环状态，不应在升级后被批量重复执行。
UPDATE meetings SET closure_status = 'completed' WHERE status = 'done';

ALTER TABLE memory_items
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'confirmed',
    ADD COLUMN confirmed_by VARCHAR(64) DEFAULT NULL,
    ADD COLUMN confirmed_at DATETIME DEFAULT NULL,
    ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    ADD INDEX idx_memory_items_status (store_id, customer_id, status);

ALTER TABLE ai_action_proposals
    ADD COLUMN assigned_to VARCHAR(64) DEFAULT NULL,
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'normal',
    ADD INDEX idx_ai_action_proposals_assignee (store_id, assigned_to, status);

-- 提案确认后要把优先级带入正式任务，不能只停留在提案卡片。
ALTER TABLE tasks
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'normal',
    ADD INDEX idx_tasks_priority (store_id, status, priority, due_at);

ALTER TABLE knowledge_chunks
    ADD COLUMN embedding MEDIUMTEXT DEFAULT NULL,
    ADD COLUMN embedding_model VARCHAR(64) DEFAULT NULL;

-- 低质量 AI 回答和会谈缺口需要可追溯且不可重复地进入同一个审核队列。
ALTER TABLE knowledge_gaps
    ADD COLUMN source_type VARCHAR(64) DEFAULT NULL,
    ADD COLUMN source_id VARCHAR(64) DEFAULT NULL,
    ADD INDEX idx_knowledge_gaps_source (store_id, source_type, source_id);
