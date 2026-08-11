-- 历史版本：旧库曾为分数列设置 DEFAULT 60，需要清除（NULL 表示“未评估/评估不完整”，看板 AVG 自然忽略）。
-- 全新库中这些列由 DataInitializer 以无默认值方式补加（见 DataInitializer.addColumnIfMissing），
-- 不存在历史遗留默认值，因此本迁移为 no-op，避免全量迁移时因列不存在而失败（Unknown column 'quality_score'）。
SELECT 1;
