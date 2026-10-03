-- 为统一的模型/服务分组同步任务补充服务分组维度统计。
-- 同步配置和调度器仍保持单例，避免两个任务并发写 routing_groups 与 routing_group_models。

ALTER TABLE model_sync_runs
    ADD COLUMN group_total INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN group_inserted_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN group_updated_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN group_unchanged_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN group_stale_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT model_sync_runs_group_counts_non_negative CHECK (
        group_total >= 0
        AND group_inserted_count >= 0
        AND group_updated_count >= 0
        AND group_unchanged_count >= 0
        AND group_stale_count >= 0
    );

COMMENT ON COLUMN public.model_sync_runs.group_total IS '本轮上游可见服务分组快照总数。';
COMMENT ON COLUMN public.model_sync_runs.group_inserted_count IS '本轮新建并绑定上游身份的服务分组数量。';
COMMENT ON COLUMN public.model_sync_runs.group_updated_count IS '本轮上游信息变化或首次绑定上游身份的服务分组数量。';
COMMENT ON COLUMN public.model_sync_runs.group_unchanged_count IS '本轮上游内容未变化、仅刷新发现时间的服务分组数量。';
COMMENT ON COLUMN public.model_sync_runs.group_stale_count IS '本轮因上游完整快照不再包含而转为 stale 的服务分组数量。';
