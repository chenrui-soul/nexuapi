-- 多币种财务基础：保存汇率、供应商账单导入及逐笔对账快照。
-- 本迁移不接入支付渠道；支付宝和外部告警仍由后续阶段处理。

CREATE TABLE currency_exchange_rates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency VARCHAR(3) NOT NULL,
    quote_currency VARCHAR(3) NOT NULL,
    rate NUMERIC(30,12) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'manual',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT currency_exchange_rate_currency_valid CHECK (
        base_currency ~ '^[A-Z]{3}$' AND quote_currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT currency_exchange_rate_pair_distinct CHECK (base_currency <> quote_currency),
    CONSTRAINT currency_exchange_rate_positive CHECK (rate > 0),
    CONSTRAINT currency_exchange_rate_source_valid CHECK (source ~ '^[a-zA-Z0-9_.-]{1,64}$')
);
CREATE INDEX idx_currency_exchange_rates_lookup
    ON currency_exchange_rates (base_currency, quote_currency, effective_at DESC);

CREATE TABLE supplier_billing_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id UUID NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    billing_currency VARCHAR(3) NOT NULL,
    period_from DATE NOT NULL,
    period_to DATE NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'imported',
    row_count INTEGER NOT NULL DEFAULT 0,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT supplier_billing_import_file_hash_valid CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT supplier_billing_import_currency_valid CHECK (billing_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT supplier_billing_import_period_valid CHECK (period_to >= period_from),
    CONSTRAINT supplier_billing_import_status_valid CHECK (status IN ('imported','validated','reconciled','rejected')),
    CONSTRAINT supplier_billing_import_row_count_valid CHECK (row_count >= 0),
    CONSTRAINT supplier_billing_import_unique_file UNIQUE (supplier_id, file_sha256)
);

CREATE TABLE supplier_billing_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id UUID NOT NULL REFERENCES supplier_billing_imports(id) ON DELETE CASCADE,
    provider_reference VARCHAR(160) NOT NULL,
    request_id VARCHAR(80),
    billed_at TIMESTAMPTZ,
    model_name VARCHAR(160),
    quantity NUMERIC(30,12) NOT NULL DEFAULT 0,
    amount NUMERIC(30,12) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT supplier_billing_item_amount_nonnegative CHECK (amount >= 0),
    CONSTRAINT supplier_billing_item_quantity_nonnegative CHECK (quantity >= 0),
    CONSTRAINT supplier_billing_item_currency_valid CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT supplier_billing_item_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT supplier_billing_item_reference_unique UNIQUE (import_id, provider_reference)
);
CREATE INDEX idx_supplier_billing_items_request ON supplier_billing_items (request_id);

CREATE TABLE financial_reconciliation_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id UUID NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    billing_item_id UUID REFERENCES supplier_billing_items(id) ON DELETE SET NULL,
    request_id VARCHAR(80),
    local_amount NUMERIC(30,12),
    local_currency VARCHAR(3),
    provider_amount NUMERIC(30,12),
    provider_currency VARCHAR(3),
    exchange_rate NUMERIC(30,12),
    tax_amount NUMERIC(30,12) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    discrepancy_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT financial_reconciliation_amounts_nonnegative CHECK (
        (local_amount IS NULL OR local_amount >= 0)
        AND (provider_amount IS NULL OR provider_amount >= 0)
        AND tax_amount >= 0
    ),
    CONSTRAINT financial_reconciliation_currency_valid CHECK (
        (local_currency IS NULL OR local_currency ~ '^[A-Z]{3}$')
        AND (provider_currency IS NULL OR provider_currency ~ '^[A-Z]{3}$')
    ),
    CONSTRAINT financial_reconciliation_rate_valid CHECK (exchange_rate IS NULL OR exchange_rate > 0),
    CONSTRAINT financial_reconciliation_status_valid CHECK (
        status IN ('matched','missing_local','missing_provider','amount_mismatch','currency_missing','rejected')
    )
);
CREATE INDEX idx_financial_reconciliation_supplier_time
    ON financial_reconciliation_records (supplier_id, created_at DESC);
CREATE INDEX idx_financial_reconciliation_request
    ON financial_reconciliation_records (request_id);

COMMENT ON TABLE currency_exchange_rates IS '多币种财务汇率表；结算时读取并把命中汇率写入对账快照。';
COMMENT ON COLUMN currency_exchange_rates.base_currency IS '换算源币种三位大写代码。';
COMMENT ON COLUMN currency_exchange_rates.quote_currency IS '换算目标币种三位大写代码。';
COMMENT ON COLUMN currency_exchange_rates.rate IS '1 个源币种对应的目标币种数量。';
COMMENT ON COLUMN currency_exchange_rates.effective_at IS '该汇率开始生效的时间；历史账单不得回读当前汇率。';
COMMENT ON COLUMN currency_exchange_rates.source IS '汇率来源，例如 manual 或 provider_feed，不保存凭证。';

COMMENT ON TABLE supplier_billing_imports IS '供应商账单文件导入批次；文件只保存摘要和元数据，不保存原始密钥。';
COMMENT ON COLUMN supplier_billing_imports.file_sha256 IS '账单文件 SHA-256，用于幂等导入和重复文件拒绝。';
COMMENT ON COLUMN supplier_billing_imports.billing_currency IS '本批供应商账单币种。';
COMMENT ON COLUMN supplier_billing_imports.status IS '导入状态：imported、validated、reconciled 或 rejected。';

COMMENT ON TABLE supplier_billing_items IS '供应商账单逐笔明细；provider_reference 在同一导入批次内幂等。';
COMMENT ON COLUMN supplier_billing_items.provider_reference IS '供应商账单中的交易或调用参考号。';
COMMENT ON COLUMN supplier_billing_items.request_id IS '可关联的平台请求 ID；无法关联时为空。';
COMMENT ON COLUMN supplier_billing_items.metadata IS '脱敏的供应商扩展字段，禁止保存 API Key、Token 或密码。';

COMMENT ON TABLE financial_reconciliation_records IS '平台请求与供应商账单的逐笔多币种对账结果，含实际采用的汇率和税费快照。';
COMMENT ON COLUMN financial_reconciliation_records.exchange_rate IS '本次对账实际采用的汇率快照，不随汇率表后续修改。';
COMMENT ON COLUMN financial_reconciliation_records.tax_amount IS '按对账时规则计算并固化的税费金额。';
COMMENT ON COLUMN financial_reconciliation_records.status IS '对账结论：matched、missing_local、missing_provider、amount_mismatch、currency_missing 或 rejected。';
COMMENT ON COLUMN financial_reconciliation_records.discrepancy_reason IS '脱敏差异说明，禁止包含供应商凭证或完整账单原文。';
