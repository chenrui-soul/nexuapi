-- Wave 5B：供应商管理、渠道归属、成本快照和逐次上游调用记录。
-- 历史迁移保持不可变；系统保留供应商用于无损承接 V6 之前创建的渠道。

CREATE TABLE suppliers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    supplier_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'disabled',
    health_status VARCHAR(24) NOT NULL DEFAULT 'unconfigured',
    billing_mode VARCHAR(32) NOT NULL,
    settlement_currency VARCHAR(3) NOT NULL,
    disabled_reason VARCHAR(500),
    disabled_at TIMESTAMPTZ,
    last_health_checked_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT suppliers_type_valid CHECK (supplier_type IN ('direct', 'reseller', 'aggregator', 'other')),
    CONSTRAINT suppliers_status_valid CHECK (status IN ('active', 'disabled', 'suspended', 'terminated')),
    CONSTRAINT suppliers_health_valid CHECK (health_status IN ('healthy', 'degraded', 'unavailable', 'unconfigured')),
    CONSTRAINT suppliers_billing_mode_valid CHECK (billing_mode IN ('prepaid', 'postpaid', 'monthly_settlement', 'other')),
    CONSTRAINT suppliers_currency_valid CHECK (settlement_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT suppliers_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT suppliers_disabled_reason_consistent CHECK (
        status = 'active' OR disabled_reason IS NOT NULL
    )
);

CREATE UNIQUE INDEX uk_suppliers_code_ci ON suppliers (lower(code));
CREATE UNIQUE INDEX uk_suppliers_name_ci ON suppliers (lower(name));
CREATE INDEX idx_suppliers_admin_list ON suppliers (status, health_status, updated_at DESC);

INSERT INTO suppliers (
    id, code, name, supplier_type, status, health_status,
    billing_mode, settlement_currency, disabled_reason, metadata
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'legacy-unassigned',
    '历史未分类供应商',
    'other',
    'active',
    'unconfigured',
    'other',
    'USD',
    NULL,
    '{"system_reserved":true}'::jsonb
);

ALTER TABLE channels
    ADD COLUMN supplier_id UUID;

UPDATE channels
   SET supplier_id = '00000000-0000-0000-0000-000000000001'
 WHERE supplier_id IS NULL;

ALTER TABLE channels
    ALTER COLUMN supplier_id SET NOT NULL,
    ADD CONSTRAINT fk_channels_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE RESTRICT;

CREATE INDEX idx_channels_supplier ON channels (supplier_id, status, priority, weight);

-- 历史渠道模型没有缓存成本价时，沿用普通输入成本，避免缓存 Token 被错误记为零成本。
ALTER TABLE channel_models
    ADD COLUMN cost_cached_input_price NUMERIC(20, 10);

UPDATE channel_models
   SET cost_cached_input_price = cost_input_price
 WHERE cost_cached_input_price IS NULL;

ALTER TABLE channel_models
    ALTER COLUMN cost_cached_input_price SET DEFAULT 0,
    ALTER COLUMN cost_cached_input_price SET NOT NULL,
    ADD CONSTRAINT channel_models_cached_cost_non_negative
        CHECK (cost_cached_input_price >= 0) NOT VALID;

ALTER TABLE request_logs
    ADD COLUMN supplier_id UUID REFERENCES suppliers(id),
    ADD COLUMN channel_model_id UUID REFERENCES channel_models(id),
    ADD COLUMN supplier_input_price NUMERIC(20, 10),
    ADD COLUMN supplier_cached_input_price NUMERIC(20, 10),
    ADD COLUMN supplier_output_price NUMERIC(20, 10),
    ADD COLUMN supplier_cost_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN supplier_cost_currency VARCHAR(3),
    ADD COLUMN gross_margin_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN supplier_error_category VARCHAR(64),
    ADD CONSTRAINT request_logs_supplier_prices_non_negative CHECK (
        (supplier_input_price IS NULL OR supplier_input_price >= 0)
        AND (supplier_cached_input_price IS NULL OR supplier_cached_input_price >= 0)
        AND (supplier_output_price IS NULL OR supplier_output_price >= 0)
        AND supplier_cost_amount >= 0
    ) NOT VALID,
    ADD CONSTRAINT request_logs_supplier_currency_valid CHECK (
        supplier_cost_currency IS NULL OR supplier_cost_currency ~ '^[A-Z]{3}$'
    ) NOT VALID;

CREATE INDEX idx_request_logs_supplier_time ON request_logs (supplier_id, created_at DESC);
CREATE INDEX idx_request_logs_channel_model_time ON request_logs (channel_model_id, created_at DESC);

CREATE TABLE upstream_attempt_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id VARCHAR(80) NOT NULL,
    supplier_id UUID NOT NULL REFERENCES suppliers(id),
    channel_id UUID NOT NULL REFERENCES channels(id),
    channel_model_id UUID NOT NULL REFERENCES channel_models(id),
    model_id UUID NOT NULL REFERENCES ai_models(id),
    attempt_no INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    upstream_status INTEGER,
    error_category VARCHAR(64),
    error_summary VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT upstream_attempt_number_valid CHECK (attempt_no >= 0),
    CONSTRAINT upstream_attempt_duration_valid CHECK (duration_ms >= 0),
    CONSTRAINT upstream_attempt_outcome_valid CHECK (
        outcome IN ('success', 'supplier_failure', 'upstream_rejected', 'platform_failure', 'client_cancelled')
    )
);

CREATE INDEX idx_upstream_attempt_supplier_time
    ON upstream_attempt_logs (supplier_id, created_at DESC);
CREATE INDEX idx_upstream_attempt_request
    ON upstream_attempt_logs (request_id, attempt_no);
CREATE INDEX idx_upstream_attempt_supplier_outcome_time
    ON upstream_attempt_logs (supplier_id, outcome, created_at DESC);

ALTER TABLE usage_aggregates
    ADD COLUMN failure_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN supplier_failure_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN supplier_cost_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN gross_margin_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    ADD CONSTRAINT usage_aggregates_failure_counts_valid CHECK (
        failure_count >= 0 AND supplier_failure_count >= 0 AND supplier_failure_count <= failure_count
    ) NOT VALID,
    ADD CONSTRAINT usage_aggregates_supplier_cost_valid CHECK (supplier_cost_amount >= 0) NOT VALID;

COMMENT ON TABLE public.suppliers IS '实际商业合作与结算供应商表，一个供应商可以关联多个上游渠道。';
COMMENT ON COLUMN public.suppliers.id IS '供应商全局唯一标识。';
COMMENT ON COLUMN public.suppliers.code IS '供应商稳定业务编码，忽略大小写后全局唯一。';
COMMENT ON COLUMN public.suppliers.name IS '管理端显示的供应商名称，忽略大小写后全局唯一。';
COMMENT ON COLUMN public.suppliers.supplier_type IS '供应商类型：direct、reseller、aggregator 或 other。';
COMMENT ON COLUMN public.suppliers.status IS '人工维护的合作状态：active、disabled、suspended 或 terminated。';
COMMENT ON COLUMN public.suppliers.health_status IS '自动健康状态：healthy、degraded、unavailable 或 unconfigured；与合作状态独立。';
COMMENT ON COLUMN public.suppliers.billing_mode IS '供应商结算方式：prepaid、postpaid、monthly_settlement 或 other。';
COMMENT ON COLUMN public.suppliers.settlement_currency IS '供应商结算币种的三位大写代码；P0 不执行自动汇率换算。';
COMMENT ON COLUMN public.suppliers.disabled_reason IS '供应商非 active 时的停用、暂停或终止原因，不得包含凭证。';
COMMENT ON COLUMN public.suppliers.disabled_at IS '供应商最近一次从 active 切换为非 active 的时间。';
COMMENT ON COLUMN public.suppliers.last_health_checked_at IS '最近一次自动健康聚合或探测完成时间，由 Wave 7A 维护。';
COMMENT ON COLUMN public.suppliers.metadata IS '供应商非敏感扩展元数据 JSON，禁止保存 API Key、Token、密码或结算凭证。';
COMMENT ON COLUMN public.suppliers.created_at IS '供应商记录创建时间。';
COMMENT ON COLUMN public.suppliers.updated_at IS '供应商记录最后更新时间。';
COMMENT ON COLUMN public.suppliers.version IS '乐观锁版本号，防止并发状态和结算配置相互覆盖。';

COMMENT ON COLUMN public.channels.supplier_id IS '实际提供该渠道并负责商业结算的供应商标识。';
COMMENT ON COLUMN public.channel_models.cost_cached_input_price IS '上游缓存输入 Token 成本价，计价单位与平台模型 price_unit 保持一致。';

COMMENT ON COLUMN public.request_logs.supplier_id IS '最终实际调用渠道所属供应商标识快照。';
COMMENT ON COLUMN public.request_logs.channel_model_id IS '最终实际使用的渠道模型映射标识。';
COMMENT ON COLUMN public.request_logs.supplier_input_price IS '本次调用采用的供应商普通输入 Token 成本单价快照。';
COMMENT ON COLUMN public.request_logs.supplier_cached_input_price IS '本次调用采用的供应商缓存输入 Token 成本单价快照。';
COMMENT ON COLUMN public.request_logs.supplier_output_price IS '本次调用采用的供应商输出 Token 成本单价快照。';
COMMENT ON COLUMN public.request_logs.supplier_cost_amount IS '按实际 Token 和成本单价计算的供应商成本金额。';
COMMENT ON COLUMN public.request_logs.supplier_cost_currency IS '供应商成本的结算币种快照。';
COMMENT ON COLUMN public.request_logs.gross_margin_amount IS '平台结算金额减供应商成本后的毛利金额；P0 要求使用统一核算口径。';
COMMENT ON COLUMN public.request_logs.supplier_error_category IS '仅在最终错误归责供应商时记录的归一化错误分类。';

COMMENT ON COLUMN public.request_logs_default.supplier_id IS '继承自主表的供应商标识快照。';
COMMENT ON COLUMN public.request_logs_default.channel_model_id IS '继承自主表的渠道模型映射标识。';
COMMENT ON COLUMN public.request_logs_default.supplier_input_price IS '继承自主表的供应商普通输入成本单价快照。';
COMMENT ON COLUMN public.request_logs_default.supplier_cached_input_price IS '继承自主表的供应商缓存输入成本单价快照。';
COMMENT ON COLUMN public.request_logs_default.supplier_output_price IS '继承自主表的供应商输出成本单价快照。';
COMMENT ON COLUMN public.request_logs_default.supplier_cost_amount IS '继承自主表的供应商成本金额。';
COMMENT ON COLUMN public.request_logs_default.supplier_cost_currency IS '继承自主表的供应商成本币种。';
COMMENT ON COLUMN public.request_logs_default.gross_margin_amount IS '继承自主表的毛利金额。';
COMMENT ON COLUMN public.request_logs_default.supplier_error_category IS '继承自主表的供应商错误分类。';

COMMENT ON TABLE public.upstream_attempt_logs IS '每次真实上游调用尝试的脱敏明细，用于统计重试场景下供应商稳定性。';
COMMENT ON COLUMN public.upstream_attempt_logs.id IS '上游尝试记录全局唯一标识。';
COMMENT ON COLUMN public.upstream_attempt_logs.request_id IS '关联最终调用日志和计费链路的平台请求标识。';
COMMENT ON COLUMN public.upstream_attempt_logs.supplier_id IS '本次上游尝试使用的供应商标识。';
COMMENT ON COLUMN public.upstream_attempt_logs.channel_id IS '本次上游尝试使用的渠道标识。';
COMMENT ON COLUMN public.upstream_attempt_logs.channel_model_id IS '本次上游尝试使用的渠道模型映射标识。';
COMMENT ON COLUMN public.upstream_attempt_logs.model_id IS '客户端请求的平台模型标识。';
COMMENT ON COLUMN public.upstream_attempt_logs.attempt_no IS '从零开始的上游尝试序号，与 Gateway retry_count 口径一致。';
COMMENT ON COLUMN public.upstream_attempt_logs.started_at IS '开始向上游发起本次尝试的时间。';
COMMENT ON COLUMN public.upstream_attempt_logs.completed_at IS '本次上游尝试成功或失败终止的时间。';
COMMENT ON COLUMN public.upstream_attempt_logs.duration_ms IS '本次上游尝试耗时，单位毫秒。';
COMMENT ON COLUMN public.upstream_attempt_logs.outcome IS '尝试结果：success、supplier_failure、upstream_rejected、platform_failure 或 client_cancelled。';
COMMENT ON COLUMN public.upstream_attempt_logs.upstream_status IS '上游返回的 HTTP 状态码；网络、超时或平台错误时为空。';
COMMENT ON COLUMN public.upstream_attempt_logs.error_category IS '用于稳定性统计的归一化错误分类；非供应商责任时为空。';
COMMENT ON COLUMN public.upstream_attempt_logs.error_summary IS '上游错误脱敏摘要，禁止包含响应正文、Token、请求内容或渠道凭证。';
COMMENT ON COLUMN public.upstream_attempt_logs.created_at IS '上游尝试日志写入时间。';

COMMENT ON COLUMN public.usage_aggregates.failure_count IS '时间桶内所有失败请求数量。';
COMMENT ON COLUMN public.usage_aggregates.supplier_failure_count IS '时间桶内归责供应商的失败数量。';
COMMENT ON COLUMN public.usage_aggregates.supplier_cost_amount IS '时间桶内供应商成本金额合计。';
COMMENT ON COLUMN public.usage_aggregates.gross_margin_amount IS '时间桶内毛利金额合计。';

COMMENT ON COLUMN public.usage_aggregates.dimension_type IS '聚合维度类型，例如 user、api_key、model、channel、group 或 supplier。';
