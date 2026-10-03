-- V18 回滚说明：仅适用于尚未依赖 V18 价格版本进行正式结算的环境。
-- 正式环境优先恢复迁移前完整 PostgreSQL 备份，禁止删除已用于财务对账的历史快照。

-- 回滚会删除价格版本和请求计费快照；只要已经产生过配置或业务数据就拒绝结构回滚。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM model_pricing_versions)
       OR EXISTS (SELECT 1 FROM model_pricing_rules)
       OR EXISTS (SELECT 1 FROM model_context_tiers)
       OR EXISTS (SELECT 1 FROM supplier_model_prices)
       OR EXISTS (SELECT 1 FROM request_billing_details)
       OR EXISTS (
            SELECT 1
              FROM ai_models
             WHERE active_pricing_version_id IS NOT NULL
                OR unit_price <> 0
                OR display_original_price <> 0
                OR input_token_ratio <> 10000
                OR output_token_ratio <> 10000
                OR cached_input_token_ratio <> 10000
                OR cache_write_5m_token_ratio <> 0
                OR cache_write_1h_token_ratio <> 0
                OR charge_desc IS NOT NULL
                OR context_tier_mode <> 0
                OR pricing_unmatched_behavior <> 'base'
       ) THEN
        RAISE EXCEPTION 'V18 contains pricing configuration or billing snapshots; restore the pre-migration database backup instead';
    END IF;
END $$;

ALTER TABLE ai_models DROP CONSTRAINT IF EXISTS ai_models_active_pricing_version_fk;

DROP TABLE IF EXISTS request_billing_details;
DROP TABLE IF EXISTS supplier_model_prices;
DROP TABLE IF EXISTS model_context_tiers;
DROP TABLE IF EXISTS model_pricing_rules;
DROP TABLE IF EXISTS model_pricing_versions;

ALTER TABLE ai_models
    DROP CONSTRAINT IF EXISTS ai_models_billing_type_valid,
    DROP CONSTRAINT IF EXISTS ai_models_audio_billing_type_valid,
    DROP CONSTRAINT IF EXISTS ai_models_v2_price_nonnegative,
    DROP CONSTRAINT IF EXISTS ai_models_token_ratios_nonnegative,
    DROP CONSTRAINT IF EXISTS ai_models_context_tier_mode_valid,
    DROP CONSTRAINT IF EXISTS ai_models_pricing_unmatched_behavior_valid,
    DROP COLUMN IF EXISTS active_pricing_version_id,
    DROP COLUMN IF EXISTS pricing_unmatched_behavior,
    DROP COLUMN IF EXISTS context_tier_mode,
    DROP COLUMN IF EXISTS charge_desc,
    DROP COLUMN IF EXISTS cache_write_1h_token_ratio,
    DROP COLUMN IF EXISTS cache_write_5m_token_ratio,
    DROP COLUMN IF EXISTS cached_input_token_ratio,
    DROP COLUMN IF EXISTS output_token_ratio,
    DROP COLUMN IF EXISTS input_token_ratio,
    DROP COLUMN IF EXISTS display_original_price,
    DROP COLUMN IF EXISTS unit_price,
    DROP COLUMN IF EXISTS billing_type;
