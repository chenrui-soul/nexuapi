-- V12 回滚会删除上游分组溯源字段和分组模型目录关联。
-- 执行前必须确认不再需要保留已同步的上游分组 ID 和健康历史。

DROP TABLE IF EXISTS routing_group_models;

DROP INDEX IF EXISTS idx_routing_groups_source_sync;
DROP INDEX IF EXISTS uk_routing_groups_source_identity;

ALTER TABLE routing_groups
    DROP COLUMN IF EXISTS source_managed,
    DROP COLUMN IF EXISTS source_synced_at,
    DROP COLUMN IF EXISTS source_last_seen_at,
    DROP COLUMN IF EXISTS source_payload_hash,
    DROP COLUMN IF EXISTS source_metadata,
    DROP COLUMN IF EXISTS source_billing_type,
    DROP COLUMN IF EXISTS source_rate,
    DROP COLUMN IF EXISTS source_status,
    DROP COLUMN IF EXISTS sync_source,
    DROP COLUMN IF EXISTS source_group_name,
    DROP COLUMN IF EXISTS source_group_id,
    DROP COLUMN IF EXISTS source_supplier_id;
