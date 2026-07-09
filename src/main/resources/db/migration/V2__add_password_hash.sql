-- V1 已建 users 表并含 password_hash 列；此处仅幂等补全演示账号密码
-- （老 Supabase 迁移里曾有 ADD COLUMN IF NOT EXISTS，MySQL 下由 V1 统一建表，故省略）
UPDATE users
SET password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
WHERE password_hash IS NULL OR password_hash = '';
