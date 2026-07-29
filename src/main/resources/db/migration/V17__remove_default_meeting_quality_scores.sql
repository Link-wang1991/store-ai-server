-- 未经完整量表评估的会谈不能自动显示为 60 分。
-- NULL 表示“未评估/评估不完整”，看板 AVG 会自然忽略该值。

ALTER TABLE meetings
    ALTER COLUMN quality_score DROP DEFAULT;

ALTER TABLE meeting_analysis
    ALTER COLUMN need_digging_score DROP DEFAULT,
    ALTER COLUMN deal_advancing_score DROP DEFAULT,
    ALTER COLUMN compliance_score DROP DEFAULT,
    ALTER COLUMN service_score DROP DEFAULT,
    ALTER COLUMN quality_score DROP DEFAULT;
