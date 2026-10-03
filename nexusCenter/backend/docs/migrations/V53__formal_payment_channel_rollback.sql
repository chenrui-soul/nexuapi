-- V53 回滚仅用于尚未产生真实支付事件的环境。
-- 正式环境若已存在支付或退款记录，必须先导出并完成资金对账，禁止直接执行。

DROP TABLE IF EXISTS payment_reconciliation_records;
DROP TABLE IF EXISTS payment_events;

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_source_valid;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_source_valid
    CHECK (source IN ('admin', 'mock_payment', 'migration'));

COMMENT ON COLUMN subscriptions.source IS '订阅创建来源：admin、mock_payment 或 migration。';
