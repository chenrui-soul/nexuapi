-- 仅供人工回滚 Wave 5B；执行前必须确认没有后续迁移依赖这些表和字段。

ALTER TABLE usage_aggregates
    DROP CONSTRAINT IF EXISTS usage_aggregates_supplier_cost_valid,
    DROP CONSTRAINT IF EXISTS usage_aggregates_failure_counts_valid,
    DROP COLUMN IF EXISTS gross_margin_amount,
    DROP COLUMN IF EXISTS supplier_cost_amount,
    DROP COLUMN IF EXISTS supplier_failure_count,
    DROP COLUMN IF EXISTS failure_count;

DROP TABLE IF EXISTS upstream_attempt_logs;

DROP INDEX IF EXISTS idx_request_logs_channel_model_time;
DROP INDEX IF EXISTS idx_request_logs_supplier_time;

ALTER TABLE request_logs
    DROP CONSTRAINT IF EXISTS request_logs_supplier_currency_valid,
    DROP CONSTRAINT IF EXISTS request_logs_supplier_prices_non_negative,
    DROP COLUMN IF EXISTS supplier_error_category,
    DROP COLUMN IF EXISTS gross_margin_amount,
    DROP COLUMN IF EXISTS supplier_cost_currency,
    DROP COLUMN IF EXISTS supplier_cost_amount,
    DROP COLUMN IF EXISTS supplier_output_price,
    DROP COLUMN IF EXISTS supplier_cached_input_price,
    DROP COLUMN IF EXISTS supplier_input_price,
    DROP COLUMN IF EXISTS channel_model_id,
    DROP COLUMN IF EXISTS supplier_id;

ALTER TABLE channel_models
    DROP CONSTRAINT IF EXISTS channel_models_cached_cost_non_negative,
    DROP COLUMN IF EXISTS cost_cached_input_price;

DROP INDEX IF EXISTS idx_channels_supplier;
ALTER TABLE channels
    DROP CONSTRAINT IF EXISTS fk_channels_supplier,
    DROP COLUMN IF EXISTS supplier_id;

DROP TABLE IF EXISTS suppliers;
