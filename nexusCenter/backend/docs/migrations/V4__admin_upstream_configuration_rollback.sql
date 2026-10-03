-- 仅适用于尚未依赖 Wave 5 version 字段的回滚窗口。
-- 如果已经产生管理员配置变更，先导出五张配置表和 audit_logs 再执行。

DROP INDEX IF EXISTS idx_routing_groups_admin_list;
DROP INDEX IF EXISTS idx_channels_admin_list;
DROP INDEX IF EXISTS idx_ai_models_admin_list;

ALTER TABLE group_routes
    DROP CONSTRAINT IF EXISTS group_routes_priority_non_negative,
    DROP COLUMN IF EXISTS version;

ALTER TABLE routing_groups
    DROP COLUMN IF EXISTS version;

ALTER TABLE channel_models
    DROP CONSTRAINT IF EXISTS channel_models_priority_non_negative,
    DROP CONSTRAINT IF EXISTS channel_models_costs_non_negative,
    DROP COLUMN IF EXISTS version;

ALTER TABLE channels
    DROP CONSTRAINT IF EXISTS channels_concurrency_positive,
    DROP CONSTRAINT IF EXISTS channels_priority_non_negative,
    DROP COLUMN IF EXISTS version,
    DROP COLUMN IF EXISTS credential_updated_at,
    DROP COLUMN IF EXISTS credential_fingerprint;

ALTER TABLE ai_models
    DROP CONSTRAINT IF EXISTS ai_models_prices_non_negative,
    DROP COLUMN IF EXISTS version;

