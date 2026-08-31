-- 命名规范化：去掉业务库中残留的 ai_ 前缀，统一为 plural_snake_case
-- （ai_feedback / ai_action_proposals 是业务表，不应带 ai_ 前缀）
-- 幂等：仅当旧表仍存在时才重命名，避免重复执行报错。
DROP PROCEDURE IF EXISTS rename_ai_prefixed_tables;
DELIMITER $$
CREATE PROCEDURE rename_ai_prefixed_tables()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ai_feedback') THEN
        RENAME TABLE ai_feedback TO answer_feedback;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ai_action_proposals') THEN
        RENAME TABLE ai_action_proposals TO action_proposals;
    END IF;
END $$
DELIMITER ;
CALL rename_ai_prefixed_tables();
DROP PROCEDURE IF EXISTS rename_ai_prefixed_tables;
