-- V21 结构回滚脚本。
-- 执行前应先切回兼容 JAR 并完成数据库备份；本脚本不删除已生成的历史价格版本。

ALTER TABLE model_context_tiers
    DROP CONSTRAINT IF EXISTS model_context_tiers_cache_ratios_nonnegative,
    DROP COLUMN IF EXISTS cache_write_5m_ratio,
    DROP COLUMN IF EXISTS cache_write_1h_ratio;

DROP INDEX IF EXISTS uq_model_pricing_versions_source_hash;

ALTER TABLE model_pricing_versions
    DROP CONSTRAINT IF EXISTS model_pricing_versions_source_hash_valid,
    DROP CONSTRAINT IF EXISTS model_pricing_versions_source_type_valid,
    DROP COLUMN IF EXISTS source_observed_at,
    DROP COLUMN IF EXISTS source_hash,
    DROP COLUMN IF EXISTS source_type;

ALTER TABLE ai_models
    DROP CONSTRAINT IF EXISTS ai_models_pricing_source_hash_valid,
    DROP COLUMN IF EXISTS pricing_source_synced_at,
    DROP COLUMN IF EXISTS pricing_source_hash,
    DROP COLUMN IF EXISTS pricing_source_managed;
