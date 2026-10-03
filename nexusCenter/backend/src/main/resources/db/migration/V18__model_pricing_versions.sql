-- 计费 V2：新增不可变价格版本、条件规则、长上下文分档和请求计费快照。
-- 本迁移不删除、不重命名旧价格字段，Gateway 可随时切回 v1。

ALTER TABLE ai_models
    ADD COLUMN billing_type SMALLINT NOT NULL DEFAULT 4,
    ADD COLUMN unit_price NUMERIC(30, 12) NOT NULL DEFAULT 0,
    ADD COLUMN display_original_price NUMERIC(30, 12) NOT NULL DEFAULT 0,
    ADD COLUMN input_token_ratio BIGINT NOT NULL DEFAULT 10000,
    ADD COLUMN output_token_ratio BIGINT NOT NULL DEFAULT 10000,
    ADD COLUMN cached_input_token_ratio BIGINT NOT NULL DEFAULT 10000,
    ADD COLUMN cache_write_5m_token_ratio BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cache_write_1h_token_ratio BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN charge_desc VARCHAR(1000),
    ADD COLUMN context_tier_mode SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN pricing_unmatched_behavior VARCHAR(16) NOT NULL DEFAULT 'base',
    ADD COLUMN active_pricing_version_id UUID;

-- 兼容既有模型：只推导计费类型，不自动启用 V2 价格版本。
UPDATE ai_models
   SET billing_type = CASE
       WHEN capability_type = 'audio' AND price_unit NOT IN ('second', 'character') THEN 5
       WHEN price_unit = 'request' THEN 1
       WHEN price_unit = 'image' THEN 2
       WHEN price_unit = 'second' THEN 3
       WHEN price_unit = 'character' THEN 5
       ELSE 4
   END;

ALTER TABLE ai_models
    ADD CONSTRAINT ai_models_billing_type_valid CHECK (billing_type BETWEEN 1 AND 5),
    ADD CONSTRAINT ai_models_audio_billing_type_valid CHECK (
        capability_type <> 'audio' OR billing_type IN (3, 5)
    ),
    ADD CONSTRAINT ai_models_v2_price_nonnegative CHECK (
        unit_price >= 0 AND display_original_price >= 0
    ),
    ADD CONSTRAINT ai_models_token_ratios_nonnegative CHECK (
        input_token_ratio >= 0 AND output_token_ratio >= 0
        AND cached_input_token_ratio >= 0
        AND cache_write_5m_token_ratio >= 0
        AND cache_write_1h_token_ratio >= 0
    ),
    ADD CONSTRAINT ai_models_context_tier_mode_valid CHECK (context_tier_mode IN (0, 1, 2)),
    ADD CONSTRAINT ai_models_pricing_unmatched_behavior_valid CHECK (
        pricing_unmatched_behavior IN ('base', 'reject')
    );

CREATE TABLE model_pricing_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    version_no BIGINT NOT NULL,
    billing_type SMALLINT NOT NULL,
    unit_price NUMERIC(30, 12) NOT NULL,
    display_original_price NUMERIC(30, 12) NOT NULL DEFAULT 0,
    input_token_ratio BIGINT NOT NULL DEFAULT 10000,
    output_token_ratio BIGINT NOT NULL DEFAULT 10000,
    cached_input_token_ratio BIGINT NOT NULL DEFAULT 10000,
    cache_write_5m_token_ratio BIGINT NOT NULL DEFAULT 0,
    cache_write_1h_token_ratio BIGINT NOT NULL DEFAULT 0,
    charge_desc VARCHAR(1000),
    context_tier_mode SMALLINT NOT NULL DEFAULT 0,
    unmatched_behavior VARCHAR(16) NOT NULL DEFAULT 'base',
    change_note VARCHAR(500),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (model_id, version_no),
    CONSTRAINT model_pricing_versions_billing_type_valid CHECK (billing_type BETWEEN 1 AND 5),
    CONSTRAINT model_pricing_versions_price_nonnegative CHECK (
        unit_price >= 0 AND display_original_price >= 0
    ),
    CONSTRAINT model_pricing_versions_ratios_nonnegative CHECK (
        input_token_ratio >= 0 AND output_token_ratio >= 0
        AND cached_input_token_ratio >= 0
        AND cache_write_5m_token_ratio >= 0
        AND cache_write_1h_token_ratio >= 0
    ),
    CONSTRAINT model_pricing_versions_context_tier_mode_valid CHECK (context_tier_mode IN (0, 1, 2)),
    CONSTRAINT model_pricing_versions_unmatched_behavior_valid CHECK (unmatched_behavior IN ('base', 'reject'))
);

ALTER TABLE ai_models
    ADD CONSTRAINT ai_models_active_pricing_version_fk
        FOREIGN KEY (active_pricing_version_id) REFERENCES model_pricing_versions(id) ON DELETE SET NULL;

CREATE INDEX idx_model_pricing_versions_model_time
    ON model_pricing_versions (model_id, version_no DESC);

CREATE TABLE model_pricing_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pricing_version_id UUID NOT NULL REFERENCES model_pricing_versions(id) ON DELETE CASCADE,
    priority INTEGER NOT NULL DEFAULT 100,
    name VARCHAR(120) NOT NULL,
    match_conditions JSONB NOT NULL DEFAULT '{}'::jsonb,
    billing_type SMALLINT,
    unit_price NUMERIC(30, 12),
    price_multiplier NUMERIC(20, 10) NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT model_pricing_rules_priority_nonnegative CHECK (priority >= 0),
    CONSTRAINT model_pricing_rules_billing_type_valid CHECK (billing_type IS NULL OR billing_type BETWEEN 1 AND 5),
    CONSTRAINT model_pricing_rules_price_nonnegative CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT model_pricing_rules_multiplier_positive CHECK (price_multiplier > 0)
);

CREATE INDEX idx_model_pricing_rules_version_priority
    ON model_pricing_rules (pricing_version_id, priority, id);

CREATE TABLE model_context_tiers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pricing_version_id UUID NOT NULL REFERENCES model_pricing_versions(id) ON DELETE CASCADE,
    priority INTEGER NOT NULL DEFAULT 100,
    min_input_tokens BIGINT NOT NULL DEFAULT 0,
    max_input_tokens BIGINT,
    input_ratio BIGINT NOT NULL DEFAULT 10000,
    output_ratio BIGINT NOT NULL DEFAULT 10000,
    cached_input_ratio BIGINT NOT NULL DEFAULT 10000,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT model_context_tiers_priority_nonnegative CHECK (priority >= 0),
    CONSTRAINT model_context_tiers_range_valid CHECK (
        min_input_tokens >= 0 AND (max_input_tokens IS NULL OR max_input_tokens > min_input_tokens)
    ),
    CONSTRAINT model_context_tiers_ratios_nonnegative CHECK (
        input_ratio >= 0 AND output_ratio >= 0 AND cached_input_ratio >= 0
    )
);

CREATE INDEX idx_model_context_tiers_version_range
    ON model_context_tiers (pricing_version_id, min_input_tokens, priority, id);

CREATE TABLE supplier_model_prices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id UUID NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
    model_id UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    source_version VARCHAR(160),
    billing_type SMALLINT,
    unit_price NUMERIC(30, 12),
    currency VARCHAR(16),
    raw_pricing JSONB NOT NULL DEFAULT '{}'::jsonb,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT supplier_model_prices_billing_type_valid CHECK (billing_type IS NULL OR billing_type BETWEEN 1 AND 5),
    CONSTRAINT supplier_model_prices_price_nonnegative CHECK (unit_price IS NULL OR unit_price >= 0)
);

CREATE INDEX idx_supplier_model_prices_lookup
    ON supplier_model_prices (supplier_id, model_id, observed_at DESC);

CREATE TABLE request_billing_details (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id VARCHAR(80) NOT NULL UNIQUE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    api_key_id UUID REFERENCES api_keys(id) ON DELETE SET NULL,
    model_id UUID REFERENCES ai_models(id) ON DELETE SET NULL,
    group_id UUID REFERENCES routing_groups(id) ON DELETE SET NULL,
    pricing_version_id UUID REFERENCES model_pricing_versions(id) ON DELETE SET NULL,
    matched_rule_id UUID REFERENCES model_pricing_rules(id) ON DELETE SET NULL,
    context_tier_id UUID REFERENCES model_context_tiers(id) ON DELETE SET NULL,
    engine_mode VARCHAR(16) NOT NULL,
    billing_type SMALLINT NOT NULL,
    base_unit_price NUMERIC(30, 12) NOT NULL,
    effective_unit_price NUMERIC(30, 12) NOT NULL,
    input_token_ratio BIGINT NOT NULL DEFAULT 0,
    output_token_ratio BIGINT NOT NULL DEFAULT 0,
    cached_input_token_ratio BIGINT NOT NULL DEFAULT 0,
    group_multiplier NUMERIC(20, 10) NOT NULL DEFAULT 1,
    usage_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    calculation_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    legacy_amount NUMERIC(30, 12),
    calculated_amount NUMERIC(30, 12) NOT NULL,
    settled_amount NUMERIC(30, 12) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT request_billing_details_engine_mode_valid CHECK (
        engine_mode IN ('v1', 'v1_fallback', 'shadow', 'v2')
    ),
    CONSTRAINT request_billing_details_billing_type_valid CHECK (billing_type BETWEEN 1 AND 5),
    CONSTRAINT request_billing_details_amounts_nonnegative CHECK (
        base_unit_price >= 0 AND effective_unit_price >= 0
        AND (legacy_amount IS NULL OR legacy_amount >= 0)
        AND calculated_amount >= 0 AND settled_amount >= 0
    ),
    CONSTRAINT request_billing_details_status_valid CHECK (status IN ('settled', 'released', 'failed'))
);

CREATE INDEX idx_request_billing_details_model_time
    ON request_billing_details (model_id, created_at DESC);
CREATE INDEX idx_request_billing_details_user_time
    ON request_billing_details (user_id, created_at DESC);

COMMENT ON COLUMN ai_models.billing_type IS '平台当前计费类型：1 按次，2 按数量/图片张数，3 按秒，4 按 Token，5 按字符';
COMMENT ON COLUMN ai_models.unit_price IS '平台当前销售基准积分单价；不等于上游供应商成本';
COMMENT ON COLUMN ai_models.display_original_price IS '仅用于用户端划线展示的原价；永不参与计费';
COMMENT ON COLUMN ai_models.input_token_ratio IS '输入 Token 倍率，万分位；10000 表示 1 倍';
COMMENT ON COLUMN ai_models.output_token_ratio IS '输出 Token 倍率，万分位；10000 表示 1 倍';
COMMENT ON COLUMN ai_models.cached_input_token_ratio IS '缓存命中输入 Token 倍率，万分位';
COMMENT ON COLUMN ai_models.cache_write_5m_token_ratio IS '5 分钟缓存写入倍率，万分位；0 表示使用默认规则';
COMMENT ON COLUMN ai_models.cache_write_1h_token_ratio IS '1 小时缓存写入倍率，万分位；0 表示使用默认规则';
COMMENT ON COLUMN ai_models.charge_desc IS '管理员维护、用户端可展示的计费说明';
COMMENT ON COLUMN ai_models.context_tier_mode IS '长上下文计价方式：0/1 整次按命中档，2 分段累加';
COMMENT ON COLUMN ai_models.pricing_unmatched_behavior IS '存在条件规则但未命中时：base 使用基础价，reject 拒绝请求';
COMMENT ON COLUMN ai_models.active_pricing_version_id IS '当前生效的不可变平台价格版本；为空时继续使用旧价格字段';

COMMENT ON TABLE model_pricing_versions IS '模型平台销售价格的不可变版本；历史请求必须引用调用时版本';
COMMENT ON TABLE model_pricing_rules IS '图片质量、尺寸、视频分辨率等条件价格规则，按 priority 从小到大匹配';
COMMENT ON TABLE model_context_tiers IS '文本模型长上下文价格分档，可按整次或分段模式计算';
COMMENT ON TABLE supplier_model_prices IS '上游供应商成本价格快照，只用于成本、毛利和溯源，不参与用户售价';
COMMENT ON TABLE request_billing_details IS '一次请求采用的价格版本、规则、用量、倍率和金额快照，不保存请求正文或凭证';
