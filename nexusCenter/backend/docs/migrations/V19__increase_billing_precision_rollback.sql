-- V19 精度回滚会把 12 位小数压回 8 位，并把整数位上限恢复为 12 位。
-- 正式环境应恢复 V19 前完整数据库备份；以下 SQL 仅在每一个目标列都可无损降级时才继续。

DO $$
DECLARE
    target record;
    has_unsafe_value boolean;
BEGIN
    FOR target IN
        SELECT *
          FROM (VALUES
            ('api_keys', 'credit_limit'),
            ('wallet_accounts', 'permanent_credits'),
            ('wallet_accounts', 'expiring_credits'),
            ('wallet_accounts', 'frozen_credits'),
            ('billing_ledger', 'amount'),
            ('billing_ledger', 'balance_after'),
            ('billing_ledger', 'permanent_delta'),
            ('billing_ledger', 'expiring_delta'),
            ('billing_ledger', 'frozen_delta'),
            ('billing_ledger', 'permanent_after'),
            ('billing_ledger', 'expiring_after'),
            ('billing_ledger', 'frozen_after'),
            ('billing_ledger', 'available_after'),
            ('wallet_reservations', 'reserved_amount'),
            ('wallet_reservations', 'reserved_permanent'),
            ('wallet_reservations', 'reserved_expiring'),
            ('wallet_reservations', 'settled_amount'),
            ('wallet_reservations', 'settled_permanent'),
            ('wallet_reservations', 'settled_expiring'),
            ('wallet_reservations', 'refunded_amount'),
            ('wallet_reservations', 'refunded_permanent'),
            ('wallet_reservations', 'refunded_expiring'),
            ('billing_refunds', 'amount'),
            ('billing_refunds', 'permanent_amount'),
            ('billing_refunds', 'expiring_amount'),
            ('billing_refunds', 'cumulative_amount'),
            ('billing_refunds', 'refundable_after'),
            ('plans', 'price'),
            ('plans', 'included_credits'),
            ('orders', 'amount'),
            ('request_logs', 'billed_amount'),
            ('request_logs', 'supplier_cost_amount'),
            ('request_logs', 'gross_margin_amount'),
            ('usage_aggregates', 'billed_amount')
          ) AS target_columns(table_name, column_name)
    LOOP
        EXECUTE format(
            'SELECT EXISTS (SELECT 1 FROM %I WHERE %I IS NOT NULL AND (%I <> round(%I, 8) OR abs(%I) >= 1000000000000))',
            target.table_name,
            target.column_name,
            target.column_name,
            target.column_name,
            target.column_name
        ) INTO has_unsafe_value;

        IF has_unsafe_value THEN
            RAISE EXCEPTION 'V19 cannot safely downgrade %.%; restore the pre-migration database backup',
                target.table_name, target.column_name;
        END IF;
    END LOOP;
END $$;

ALTER TABLE usage_aggregates ALTER COLUMN billed_amount TYPE NUMERIC(20, 8);
ALTER TABLE request_logs
    ALTER COLUMN billed_amount TYPE NUMERIC(20, 8),
    ALTER COLUMN supplier_cost_amount TYPE NUMERIC(20, 8),
    ALTER COLUMN gross_margin_amount TYPE NUMERIC(20, 8);
ALTER TABLE orders ALTER COLUMN amount TYPE NUMERIC(20, 8);
ALTER TABLE plans
    ALTER COLUMN price TYPE NUMERIC(20, 8),
    ALTER COLUMN included_credits TYPE NUMERIC(20, 8);
ALTER TABLE billing_refunds
    ALTER COLUMN amount TYPE NUMERIC(20, 8),
    ALTER COLUMN permanent_amount TYPE NUMERIC(20, 8),
    ALTER COLUMN expiring_amount TYPE NUMERIC(20, 8),
    ALTER COLUMN cumulative_amount TYPE NUMERIC(20, 8),
    ALTER COLUMN refundable_after TYPE NUMERIC(20, 8);
ALTER TABLE wallet_reservations
    ALTER COLUMN reserved_amount TYPE NUMERIC(20, 8),
    ALTER COLUMN reserved_permanent TYPE NUMERIC(20, 8),
    ALTER COLUMN reserved_expiring TYPE NUMERIC(20, 8),
    ALTER COLUMN settled_amount TYPE NUMERIC(20, 8),
    ALTER COLUMN settled_permanent TYPE NUMERIC(20, 8),
    ALTER COLUMN settled_expiring TYPE NUMERIC(20, 8),
    ALTER COLUMN refunded_amount TYPE NUMERIC(20, 8),
    ALTER COLUMN refunded_permanent TYPE NUMERIC(20, 8),
    ALTER COLUMN refunded_expiring TYPE NUMERIC(20, 8);
ALTER TABLE billing_ledger
    ALTER COLUMN amount TYPE NUMERIC(20, 8),
    ALTER COLUMN balance_after TYPE NUMERIC(20, 8),
    ALTER COLUMN permanent_delta TYPE NUMERIC(20, 8),
    ALTER COLUMN expiring_delta TYPE NUMERIC(20, 8),
    ALTER COLUMN frozen_delta TYPE NUMERIC(20, 8),
    ALTER COLUMN permanent_after TYPE NUMERIC(20, 8),
    ALTER COLUMN expiring_after TYPE NUMERIC(20, 8),
    ALTER COLUMN frozen_after TYPE NUMERIC(20, 8),
    ALTER COLUMN available_after TYPE NUMERIC(20, 8);
ALTER TABLE wallet_accounts
    ALTER COLUMN permanent_credits TYPE NUMERIC(20, 8),
    ALTER COLUMN expiring_credits TYPE NUMERIC(20, 8),
    ALTER COLUMN frozen_credits TYPE NUMERIC(20, 8);
ALTER TABLE api_keys ALTER COLUMN credit_limit TYPE NUMERIC(20, 8);

COMMENT ON COLUMN wallet_accounts.permanent_credits IS '永久积分余额，最多保留 8 位小数';
COMMENT ON COLUMN wallet_accounts.expiring_credits IS '带有效期积分余额，最多保留 8 位小数';
COMMENT ON COLUMN wallet_accounts.frozen_credits IS '请求预冻结积分，最多保留 8 位小数';
COMMENT ON COLUMN billing_ledger.amount IS '本条账本总资产变化，最多保留 8 位小数';
COMMENT ON COLUMN request_logs.billed_amount IS '请求完成时的平台实际结算积分，最多保留 8 位小数';
