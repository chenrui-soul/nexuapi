-- 将积分资金链从 8 位小数安全扩容到 12 位小数。
-- PostgreSQL NUMERIC 扩宽不会丢失既有数据；正式回滚仍应优先恢复迁移前数据库备份。

ALTER TABLE api_keys
    ALTER COLUMN credit_limit TYPE NUMERIC(30, 12);

ALTER TABLE wallet_accounts
    ALTER COLUMN permanent_credits TYPE NUMERIC(30, 12),
    ALTER COLUMN expiring_credits TYPE NUMERIC(30, 12),
    ALTER COLUMN frozen_credits TYPE NUMERIC(30, 12);

ALTER TABLE billing_ledger
    ALTER COLUMN amount TYPE NUMERIC(30, 12),
    ALTER COLUMN balance_after TYPE NUMERIC(30, 12),
    ALTER COLUMN permanent_delta TYPE NUMERIC(30, 12),
    ALTER COLUMN expiring_delta TYPE NUMERIC(30, 12),
    ALTER COLUMN frozen_delta TYPE NUMERIC(30, 12),
    ALTER COLUMN permanent_after TYPE NUMERIC(30, 12),
    ALTER COLUMN expiring_after TYPE NUMERIC(30, 12),
    ALTER COLUMN frozen_after TYPE NUMERIC(30, 12),
    ALTER COLUMN available_after TYPE NUMERIC(30, 12);

ALTER TABLE wallet_reservations
    ALTER COLUMN reserved_amount TYPE NUMERIC(30, 12),
    ALTER COLUMN reserved_permanent TYPE NUMERIC(30, 12),
    ALTER COLUMN reserved_expiring TYPE NUMERIC(30, 12),
    ALTER COLUMN settled_amount TYPE NUMERIC(30, 12),
    ALTER COLUMN settled_permanent TYPE NUMERIC(30, 12),
    ALTER COLUMN settled_expiring TYPE NUMERIC(30, 12),
    ALTER COLUMN refunded_amount TYPE NUMERIC(30, 12),
    ALTER COLUMN refunded_permanent TYPE NUMERIC(30, 12),
    ALTER COLUMN refunded_expiring TYPE NUMERIC(30, 12);

ALTER TABLE billing_refunds
    ALTER COLUMN amount TYPE NUMERIC(30, 12),
    ALTER COLUMN permanent_amount TYPE NUMERIC(30, 12),
    ALTER COLUMN expiring_amount TYPE NUMERIC(30, 12),
    ALTER COLUMN cumulative_amount TYPE NUMERIC(30, 12),
    ALTER COLUMN refundable_after TYPE NUMERIC(30, 12);

ALTER TABLE plans
    ALTER COLUMN price TYPE NUMERIC(30, 12),
    ALTER COLUMN included_credits TYPE NUMERIC(30, 12);

ALTER TABLE orders
    ALTER COLUMN amount TYPE NUMERIC(30, 12);

ALTER TABLE request_logs
    ALTER COLUMN billed_amount TYPE NUMERIC(30, 12),
    ALTER COLUMN supplier_cost_amount TYPE NUMERIC(30, 12),
    ALTER COLUMN gross_margin_amount TYPE NUMERIC(30, 12);

ALTER TABLE usage_aggregates
    ALTER COLUMN billed_amount TYPE NUMERIC(30, 12);

COMMENT ON COLUMN wallet_accounts.permanent_credits IS '永久积分余额，最多保留 12 位小数';
COMMENT ON COLUMN wallet_accounts.expiring_credits IS '带有效期积分余额，最多保留 12 位小数';
COMMENT ON COLUMN wallet_accounts.frozen_credits IS '请求预冻结积分，最多保留 12 位小数';
COMMENT ON COLUMN billing_ledger.amount IS '本条账本总资产变化，最多保留 12 位小数';
COMMENT ON COLUMN request_logs.billed_amount IS '请求完成时的平台实际结算积分，最多保留 12 位小数';
