-- 系统内置销售专业知识：与门店知识库分层使用，不能替代门店价格、服务和合规口径。
ALTER TABLE playbooks
    ADD COLUMN scenario_key VARCHAR(120) NULL,
    ADD COLUMN source VARCHAR(1000) NULL,
    ADD COLUMN applicable_roles JSON NULL,
    ADD COLUMN applicable_stages JSON NULL,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active',
    ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

CREATE UNIQUE INDEX uq_playbooks_scenario_key ON playbooks (scenario_key);
CREATE INDEX idx_playbooks_status ON playbooks (status);

-- AI 教练的两类来源分别保存：门店资料和系统销售方法论，历史记录刷新后仍可追溯。
ALTER TABLE chat_messages
    ADD COLUMN methodology_sources JSON NULL;
