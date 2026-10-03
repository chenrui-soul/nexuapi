-- V5 手工回滚脚本。
-- 请先回滚所有晚于 V5 的迁移，再执行本文件，避免清除后续版本新增的字段注释。
-- COMMENT 只修改 PostgreSQL 元数据，不影响表结构和业务数据。

DO $$
DECLARE
    target_table TEXT;
    target_column TEXT;
BEGIN
    FOREACH target_table IN ARRAY ARRAY[
        'users', 'user_roles', 'api_keys', 'ai_models', 'channels', 'channel_models',
        'routing_groups', 'group_routes', 'wallet_accounts', 'billing_ledger',
        'wallet_reservations', 'billing_refunds', 'plans', 'subscriptions', 'orders',
        'request_logs', 'request_logs_default', 'usage_aggregates', 'notifications',
        'health_checks', 'audit_logs', 'outbox_events'
    ]
    LOOP
        FOR target_column IN
            SELECT a.attname
              FROM pg_attribute a
             WHERE a.attrelid = format('%I.%I', 'public', target_table)::regclass
               AND a.attnum > 0
               AND NOT a.attisdropped
        LOOP
            EXECUTE format(
                'COMMENT ON COLUMN %I.%I.%I IS NULL',
                'public', target_table, target_column
            );
        END LOOP;

        EXECUTE format('COMMENT ON TABLE %I.%I IS NULL', 'public', target_table);
    END LOOP;
END
$$;
