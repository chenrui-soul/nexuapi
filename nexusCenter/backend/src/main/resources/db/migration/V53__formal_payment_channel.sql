-- 正式支付渠道基础设施：异步通知幂等、退款渠道状态和对账差异记录。

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_source_valid;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_source_valid
    CHECK (source IN ('admin', 'mock_payment', 'alipay_payment', 'migration'));

COMMENT ON COLUMN subscriptions.source IS '订阅创建来源：admin、mock_payment、alipay_payment 或 migration。';

CREATE TABLE payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(64) NOT NULL,
    event_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'received',
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    error_message VARCHAR(500),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT payment_events_status_valid CHECK (status IN ('received', 'processed', 'ignored', 'failed')),
    CONSTRAINT payment_events_provider_event_unique UNIQUE (provider, event_id)
);

CREATE INDEX idx_payment_events_received_at ON payment_events (received_at DESC);

CREATE TABLE payment_reconciliation_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(64) NOT NULL,
    reconciliation_date DATE NOT NULL,
    provider_trade_no VARCHAR(160),
    order_id UUID REFERENCES orders(id),
    provider_amount NUMERIC(20, 8),
    local_amount NUMERIC(20, 8),
    status VARCHAR(24) NOT NULL,
    discrepancy_reason VARCHAR(500),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payment_reconciliation_status_valid CHECK (status IN ('matched', 'missing_local', 'amount_mismatch', 'status_mismatch', 'query_failed')),
    CONSTRAINT payment_reconciliation_unique UNIQUE (provider, reconciliation_date, provider_trade_no)
);

COMMENT ON TABLE payment_events IS '正式支付渠道回调事件；按渠道和事件号幂等处理，保存非敏感审计信息。';
COMMENT ON COLUMN payment_events.id IS '回调事件内部唯一标识。';
COMMENT ON COLUMN payment_events.provider IS '支付渠道标识，例如 alipay。';
COMMENT ON COLUMN payment_events.event_id IS '渠道通知事件唯一标识，重复通知不得重复入账。';
COMMENT ON COLUMN payment_events.event_type IS '渠道事件类型，例如 payment_succeeded 或 refund_succeeded。';
COMMENT ON COLUMN payment_events.payload_hash IS '原始通知内容摘要，不保存完整密钥或敏感凭证。';
COMMENT ON COLUMN payment_events.status IS '事件处理状态：received、processed、ignored 或 failed。';
COMMENT ON COLUMN payment_events.received_at IS '平台收到渠道通知的时间。';
COMMENT ON COLUMN payment_events.processed_at IS '平台完成事件处理的时间。';
COMMENT ON COLUMN payment_events.error_message IS '处理失败的脱敏原因，禁止记录支付密钥。';
COMMENT ON COLUMN payment_events.metadata IS '非敏感渠道事件元数据。';
COMMENT ON TABLE payment_reconciliation_records IS '支付和退款对账差异记录；用于主动查询后的人工或自动补偿。';
COMMENT ON COLUMN payment_reconciliation_records.id IS '对账记录内部唯一标识。';
COMMENT ON COLUMN payment_reconciliation_records.provider IS '支付渠道标识。';
COMMENT ON COLUMN payment_reconciliation_records.reconciliation_date IS '对账业务日期。';
COMMENT ON COLUMN payment_reconciliation_records.provider_trade_no IS '渠道交易号或退款单号。';
COMMENT ON COLUMN payment_reconciliation_records.order_id IS '平台订单标识，渠道找不到时为空。';
COMMENT ON COLUMN payment_reconciliation_records.provider_amount IS '渠道返回金额。';
COMMENT ON COLUMN payment_reconciliation_records.local_amount IS '平台订单或退款金额快照。';
COMMENT ON COLUMN payment_reconciliation_records.status IS '对账结论：matched、missing_local、amount_mismatch、status_mismatch 或 query_failed。';
COMMENT ON COLUMN payment_reconciliation_records.discrepancy_reason IS '差异说明。';
COMMENT ON COLUMN payment_reconciliation_records.details IS '脱敏后的对账详情。';
COMMENT ON COLUMN payment_reconciliation_records.created_at IS '对账记录创建时间。';
