-- ============================================================
-- 门店 AI 经营助手 · PostgreSQL 建表脚本（连 Supabase）
-- ============================================================

-- 启用全文模糊检索扩展
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================================
-- 1. 门店
-- ============================================================
CREATE TABLE IF NOT EXISTS stores (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    owner_id VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 2. 用户
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(100),
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_users_email ON users(email);

-- ============================================================
-- 3. 角色定义
-- ============================================================
CREATE TABLE IF NOT EXISTS roles (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) REFERENCES stores(id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    display_name VARCHAR(100),
    permissions JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_roles_store ON roles(store_id);

-- ============================================================
-- 4. 员工
-- ============================================================
CREATE TABLE IF NOT EXISTS employees (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    user_id VARCHAR(64) REFERENCES users(id) ON DELETE SET NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL CHECK (role IN ('owner','manager','consultant','beautician','receptionist','operator')),
    status VARCHAR(20) DEFAULT 'active',
    phone VARCHAR(30),
    data_scope VARCHAR(20) DEFAULT 'self',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_employees_store ON employees(store_id);
CREATE INDEX idx_employees_user ON employees(user_id);

-- ============================================================
-- 5. 知识库文档
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    category VARCHAR(100),
    status VARCHAR(20) DEFAULT 'active',
    uploaded_by VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    visible_roles TEXT[] DEFAULT '{}',
    tags TEXT,
    remark TEXT,
    file_url TEXT,
    file_type VARCHAR(20),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_kd_store ON knowledge_documents(store_id);

-- ============================================================
-- 6. 知识库片段（检索走 bigram 关键词匹配，无向量）
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    document_id VARCHAR(64) NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    seq INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_kc_store ON knowledge_chunks(store_id);
CREATE INDEX idx_kc_document ON knowledge_chunks(document_id);
CREATE INDEX idx_kc_content_trgm ON knowledge_chunks USING GIN (content gin_trgm_ops);

-- ============================================================
-- 7. 对话会话
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_sessions (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    role VARCHAR(50),
    title VARCHAR(200),
    customer_id VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_cs_store ON chat_sessions(store_id);
CREATE INDEX idx_cs_employee ON chat_sessions(employee_id);

-- ============================================================
-- 8. 对话消息
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_messages (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    session_id VARCHAR(64) NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    answer_type VARCHAR(30),
    risk_level VARCHAR(10),
    retrieved_chunks JSONB,
    customer_id VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_cm_session ON chat_messages(session_id);
CREATE INDEX idx_cm_store ON chat_messages(store_id);

-- ============================================================
-- 9. 待确认问题
-- ============================================================
CREATE TABLE IF NOT EXISTS pending_questions (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    question TEXT NOT NULL,
    ai_suggestion TEXT,
    status VARCHAR(20) DEFAULT 'pending',
    assigned_to VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    reply TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 10. 知识库缺口
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_gaps (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    question TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 11. 风险记录
-- ============================================================
CREATE TABLE IF NOT EXISTS risk_logs (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    level VARCHAR(10) DEFAULT 'L4',
    type VARCHAR(50),
    description TEXT,
    message_id VARCHAR(64) REFERENCES chat_messages(id) ON DELETE SET NULL,
    status VARCHAR(20) DEFAULT 'open',
    handled_by VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    resolution TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 12. 任务
-- ============================================================
CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    content TEXT,
    type VARCHAR(50),
    status VARCHAR(20) DEFAULT 'todo',
    assigned_to VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    created_by VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    due_at TIMESTAMPTZ,
    feedback TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_tasks_store ON tasks(store_id);

-- ============================================================
-- 13. 禁用词
-- ============================================================
CREATE TABLE IF NOT EXISTS banned_words (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    word VARCHAR(200) NOT NULL,
    created_by VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(store_id, word)
);

-- ============================================================
-- 14. 标准答案
-- ============================================================
CREATE TABLE IF NOT EXISTS standard_answers (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    source_message_id VARCHAR(64),
    created_by VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 15. 客户
-- ============================================================
CREATE TABLE IF NOT EXISTS customers (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(30),
    gender VARCHAR(10),
    age INT,
    assigned_to VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    stage VARCHAR(50),
    pool VARCHAR(50),
    tags TEXT,
    portrait JSONB,
    total_visits INT DEFAULT 0,
    last_visit_at TIMESTAMPTZ,
    next_follow_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_customers_store ON customers(store_id);
CREATE INDEX idx_customers_assigned ON customers(assigned_to);

-- ============================================================
-- 16. 客户互动时间线
-- ============================================================
CREATE TABLE IF NOT EXISTS interactions (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    customer_id VARCHAR(64) NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    type VARCHAR(50),
    content TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 17. 结构性长记忆
-- ============================================================
CREATE TABLE IF NOT EXISTS memory_items (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    customer_id VARCHAR(64) REFERENCES customers(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    scope VARCHAR(20) DEFAULT 'customer',
    key VARCHAR(200),
    value TEXT,
    confidence VARCHAR(20),
    source_type VARCHAR(50),
    source_id VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 18. 增长机会
-- ============================================================
CREATE TABLE IF NOT EXISTS opportunities (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    customer_id VARCHAR(64) REFERENCES customers(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    type VARCHAR(50),
    status VARCHAR(20) DEFAULT 'open',
    priority INT DEFAULT 0,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 19. 通知公告
-- ============================================================
CREATE TABLE IF NOT EXISTS announcements (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    content TEXT,
    type VARCHAR(50),
    priority VARCHAR(20),
    visible_roles TEXT[],
    target_employees TEXT[],
    status VARCHAR(20) DEFAULT 'active',
    created_by VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 20. 经营报告
-- ============================================================
CREATE TABLE IF NOT EXISTS reports (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    type VARCHAR(50),
    content JSONB,
    report_date DATE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 21. 门店自定义配置
-- ============================================================
CREATE TABLE IF NOT EXISTS store_config (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    category VARCHAR(100) NOT NULL,
    code VARCHAR(200) NOT NULL,
    display_name VARCHAR(200),
    enabled BOOLEAN DEFAULT TRUE,
    visible_to_staff BOOLEAN DEFAULT TRUE,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(store_id, category, code)
);

-- ============================================================
-- 22. 门店活动
-- ============================================================
CREATE TABLE IF NOT EXISTS activities (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    content TEXT,
    start_at TIMESTAMPTZ,
    end_at TIMESTAMPTZ,
    status VARCHAR(20) DEFAULT 'active',
    created_by VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 23. 会谈会话
-- ============================================================
CREATE TABLE IF NOT EXISTS meetings (
    id VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    customer_id VARCHAR(64) REFERENCES customers(id) ON DELETE SET NULL,
    scene VARCHAR(50),
    status VARCHAR(20) DEFAULT 'recording',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 24. 会谈转写
-- ============================================================
CREATE TABLE IF NOT EXISTS meeting_transcripts (
    id VARCHAR(64) PRIMARY KEY,
    meeting_id VARCHAR(64) NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    content TEXT,
    speaker VARCHAR(50),
    seq INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 25. 会谈分析
-- ============================================================
CREATE TABLE IF NOT EXISTS meeting_analysis (
    id VARCHAR(64) PRIMARY KEY,
    meeting_id VARCHAR(64) NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    report JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 26. 会谈知情同意
-- ============================================================
CREATE TABLE IF NOT EXISTS meeting_consents (
    id VARCHAR(64) PRIMARY KEY,
    meeting_id VARCHAR(64) NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    consented BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 27. 会谈访问记录（审计）
-- ============================================================
CREATE TABLE IF NOT EXISTS meeting_access_logs (
    id VARCHAR(64) PRIMARY KEY,
    meeting_id VARCHAR(64) NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    employee_id VARCHAR(64) REFERENCES employees(id) ON DELETE SET NULL,
    action VARCHAR(50),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 28. 系统增长方法论
-- ============================================================
CREATE TABLE IF NOT EXISTS playbooks (
    id VARCHAR(64) PRIMARY KEY,
    category VARCHAR(100),
    title VARCHAR(500),
    content TEXT,
    tags TEXT[],
    created_at TIMESTAMPTZ DEFAULT NOW()
);
