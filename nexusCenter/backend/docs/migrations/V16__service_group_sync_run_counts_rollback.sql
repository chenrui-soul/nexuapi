-- 仅在确认不再需要服务分组同步统计后执行；删除字段会丢失历史分组计数。
ALTER TABLE model_sync_runs
    DROP CONSTRAINT IF EXISTS model_sync_runs_group_counts_non_negative,
    DROP COLUMN IF EXISTS group_stale_count,
    DROP COLUMN IF EXISTS group_unchanged_count,
    DROP COLUMN IF EXISTS group_updated_count,
    DROP COLUMN IF EXISTS group_inserted_count,
    DROP COLUMN IF EXISTS group_total;
