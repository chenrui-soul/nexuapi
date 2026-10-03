-- V23 无损回滚脚本。
-- 出现新计费类型、非默认音频倍率或新 Token 快照时主动拒绝，避免删除已经参与结算的事实。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ai_models WHERE billing_type = 6)
       OR EXISTS (SELECT 1 FROM model_pricing_versions WHERE billing_type = 6)
       OR EXISTS (SELECT 1 FROM model_pricing_rules WHERE billing_type = 6)
       OR EXISTS (SELECT 1 FROM supplier_model_prices WHERE billing_type = 6)
       OR EXISTS (SELECT 1 FROM request_billing_details WHERE billing_type = 6)
       OR EXISTS (
            SELECT 1 FROM ai_models
             WHERE audio_input_token_ratio <> 10000 OR audio_output_token_ratio <> 10000
       )
       OR EXISTS (
            SELECT 1 FROM model_pricing_versions
             WHERE audio_input_token_ratio <> 10000 OR audio_output_token_ratio <> 10000
       )
       OR EXISTS (
            SELECT 1 FROM request_billing_details
             WHERE audio_input_token_ratio <> 0 OR audio_output_token_ratio <> 0
                OR cache_write_5m_token_ratio <> 0 OR cache_write_1h_token_ratio <> 0
       ) THEN
        RAISE EXCEPTION 'V23 contains billing facts that cannot be removed safely; restore the pre-V23 database backup';
    END IF;
END $$;

ALTER TABLE request_billing_details
    DROP CONSTRAINT request_billing_details_token_ratios_nonnegative,
    DROP CONSTRAINT request_billing_details_billing_type_valid,
    ADD CONSTRAINT request_billing_details_billing_type_valid CHECK (billing_type BETWEEN 1 AND 5),
    DROP COLUMN cache_write_1h_token_ratio,
    DROP COLUMN cache_write_5m_token_ratio,
    DROP COLUMN audio_output_token_ratio,
    DROP COLUMN audio_input_token_ratio;

ALTER TABLE supplier_model_prices
    DROP CONSTRAINT supplier_model_prices_billing_type_valid,
    ADD CONSTRAINT supplier_model_prices_billing_type_valid CHECK (
        billing_type IS NULL OR billing_type BETWEEN 1 AND 5
    );

ALTER TABLE model_pricing_rules
    DROP CONSTRAINT model_pricing_rules_billing_type_valid,
    ADD CONSTRAINT model_pricing_rules_billing_type_valid CHECK (
        billing_type IS NULL OR billing_type BETWEEN 1 AND 5
    );

ALTER TABLE model_pricing_versions
    DROP CONSTRAINT model_pricing_versions_billing_type_valid,
    DROP CONSTRAINT model_pricing_versions_ratios_nonnegative,
    ADD CONSTRAINT model_pricing_versions_billing_type_valid CHECK (billing_type BETWEEN 1 AND 5),
    ADD CONSTRAINT model_pricing_versions_ratios_nonnegative CHECK (
        input_token_ratio >= 0 AND output_token_ratio >= 0
        AND cached_input_token_ratio >= 0
        AND cache_write_5m_token_ratio >= 0
        AND cache_write_1h_token_ratio >= 0
    ),
    DROP COLUMN audio_output_token_ratio,
    DROP COLUMN audio_input_token_ratio;

ALTER TABLE ai_models
    DROP CONSTRAINT ai_models_billing_type_valid,
    DROP CONSTRAINT ai_models_audio_billing_type_valid,
    DROP CONSTRAINT ai_models_token_ratios_nonnegative,
    ADD CONSTRAINT ai_models_billing_type_valid CHECK (billing_type BETWEEN 1 AND 5),
    ADD CONSTRAINT ai_models_audio_billing_type_valid CHECK (
        capability_type <> 'audio' OR billing_type IN (3, 5)
    ),
    ADD CONSTRAINT ai_models_token_ratios_nonnegative CHECK (
        input_token_ratio >= 0 AND output_token_ratio >= 0
        AND cached_input_token_ratio >= 0
        AND cache_write_5m_token_ratio >= 0
        AND cache_write_1h_token_ratio >= 0
    ),
    DROP COLUMN audio_output_token_ratio,
    DROP COLUMN audio_input_token_ratio;
