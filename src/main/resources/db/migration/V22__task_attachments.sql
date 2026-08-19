-- 任务证据附件：员工执行任务时可上传完成凭证/证据附件。
-- 文件二进制保存在受控存储（MinIO 私有桶或本地目录），数据库只记录元数据，绝不暴露本机绝对路径。
CREATE TABLE IF NOT EXISTS task_attachments (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    uploader_id VARCHAR(64) NULL,
    original_name VARCHAR(500) NOT NULL,
    file_url TEXT NOT NULL,          -- minio objectName 或 local://key，不含绝对路径
    mime_type VARCHAR(120) NULL,
    size_bytes BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_attachments_task (store_id, task_id, created_at)
);

-- 让任务表能记录"已完成且有证据"的语义，便于详情页判断是否需要查看附件。
ALTER TABLE tasks
    ADD COLUMN has_attachments TINYINT(1) NOT NULL DEFAULT 0;
