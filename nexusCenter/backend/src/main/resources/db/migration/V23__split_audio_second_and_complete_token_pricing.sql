-- 计费 V2 补全：将音频秒从通用按秒中独立出来，并保存完整 Token 类型倍率。
-- 本迁移不删除历史价格或请求快照；历史 billing_type=3 继续兼容读取，新音频价格使用 6。

ALTER TABLE ai_models
    ADD COLUMN audio_input_token_ratio BIGINT NOT NULL DEFAULT 10000,
    ADD COLUMN audio_output_token_ratio BIGINT NOT NULL DEFAULT 10000;

ALTER TABLE model_pricing_versions
    ADD COLUMN audio_input_token_ratio BIGINT NOT NULL DEFAULT 10000,
    ADD COLUMN audio_output_token_ratio BIGINT NOT NULL DEFAULT 10000;

ALTER TABLE request_billing_details
    ADD COLUMN audio_input_token_ratio BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN audio_output_token_ratio BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cache_write_5m_token_ratio BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cache_write_1h_token_ratio BIGINT NOT NULL DEFAULT 0;

ALTER TABLE ai_models
    DROP CONSTRAINT ai_models_billing_type_valid,
    DROP CONSTRAINT ai_models_audio_billing_type_valid,
    DROP CONSTRAINT ai_models_token_ratios_nonnegative,
    ADD CONSTRAINT ai_models_billing_type_valid CHECK (billing_type BETWEEN 1 AND 6),
    ADD CONSTRAINT ai_models_audio_billing_type_valid CHECK (
        capability_type <> 'audio' OR billing_type IN (1, 3, 5, 6)
    ),
    ADD CONSTRAINT ai_models_token_ratios_nonnegative CHECK (
        input_token_ratio >= 0 AND output_token_ratio >= 0
        AND audio_input_token_ratio >= 0 AND audio_output_token_ratio >= 0
        AND cached_input_token_ratio >= 0
        AND cache_write_5m_token_ratio >= 0
        AND cache_write_1h_token_ratio >= 0
    );

ALTER TABLE model_pricing_versions
    DROP CONSTRAINT model_pricing_versions_billing_type_valid,
    DROP CONSTRAINT model_pricing_versions_ratios_nonnegative,
    ADD CONSTRAINT model_pricing_versions_billing_type_valid CHECK (billing_type BETWEEN 1 AND 6),
    ADD CONSTRAINT model_pricing_versions_ratios_nonnegative CHECK (
        input_token_ratio >= 0 AND output_token_ratio >= 0
        AND audio_input_token_ratio >= 0 AND audio_output_token_ratio >= 0
        AND cached_input_token_ratio >= 0
        AND cache_write_5m_token_ratio >= 0
        AND cache_write_1h_token_ratio >= 0
    );

ALTER TABLE model_pricing_rules
    DROP CONSTRAINT model_pricing_rules_billing_type_valid,
    ADD CONSTRAINT model_pricing_rules_billing_type_valid CHECK (
        billing_type IS NULL OR billing_type BETWEEN 1 AND 6
    );

ALTER TABLE supplier_model_prices
    DROP CONSTRAINT supplier_model_prices_billing_type_valid,
    ADD CONSTRAINT supplier_model_prices_billing_type_valid CHECK (
        billing_type IS NULL OR billing_type BETWEEN 1 AND 6
    );

ALTER TABLE request_billing_details
    DROP CONSTRAINT request_billing_details_billing_type_valid,
    ADD CONSTRAINT request_billing_details_billing_type_valid CHECK (billing_type BETWEEN 1 AND 6),
    ADD CONSTRAINT request_billing_details_token_ratios_nonnegative CHECK (
        input_token_ratio >= 0 AND output_token_ratio >= 0
        AND cached_input_token_ratio >= 0
        AND audio_input_token_ratio >= 0 AND audio_output_token_ratio >= 0
        AND cache_write_5m_token_ratio >= 0 AND cache_write_1h_token_ratio >= 0
    );

COMMENT ON COLUMN ai_models.billing_type IS '平台当前计费类型：1 按次，2 按图片张数/数量，3 按视频秒，4 按 Token，5 按字符，6 按音频秒；历史音频类型 3 兼容读取';
COMMENT ON COLUMN ai_models.audio_input_token_ratio IS '按 Token 模型的音频输入 Token 倍率，万分位；10000 表示 1 倍';
COMMENT ON COLUMN ai_models.audio_output_token_ratio IS '按 Token 模型的音频输出 Token 倍率，万分位；10000 表示 1 倍';

COMMENT ON COLUMN model_pricing_versions.billing_type IS '不可变价格版本计费类型：1 按次，2 按图片张数/数量，3 按视频秒，4 按 Token，5 按字符，6 按音频秒；历史音频类型 3 兼容读取';
COMMENT ON COLUMN model_pricing_versions.audio_input_token_ratio IS '本价格版本的音频输入 Token 倍率，万分位';
COMMENT ON COLUMN model_pricing_versions.audio_output_token_ratio IS '本价格版本的音频输出 Token 倍率，万分位';

COMMENT ON COLUMN request_billing_details.audio_input_token_ratio IS '本次请求实际采用的音频输入 Token 倍率，万分位';
COMMENT ON COLUMN request_billing_details.audio_output_token_ratio IS '本次请求实际采用的音频输出 Token 倍率，万分位';
COMMENT ON COLUMN request_billing_details.cache_write_5m_token_ratio IS '本次请求实际采用的 5 分钟缓存写入 Token 倍率，万分位；已展开默认规则';
COMMENT ON COLUMN request_billing_details.cache_write_1h_token_ratio IS '本次请求实际采用的 1 小时缓存写入 Token 倍率，万分位；已展开默认规则';
