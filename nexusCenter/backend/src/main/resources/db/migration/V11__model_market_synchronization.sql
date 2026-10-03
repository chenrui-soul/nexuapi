-- 模型市场同步：运行配置、执行历史以及模型来源快照。
-- 上游接口地址保留在服务端配置中，数据库和管理端都不能修改，避免形成 SSRF 入口。

ALTER TABLE ai_models
    ADD COLUMN sync_source VARCHAR(64),
    ADD COLUMN source_model_key VARCHAR(160),
    ADD COLUMN source_managed BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN source_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN source_payload_hash VARCHAR(64),
    ADD COLUMN source_last_seen_at TIMESTAMPTZ,
    ADD COLUMN source_synced_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uk_ai_models_sync_identity
    ON ai_models (sync_source, lower(source_model_key))
    WHERE sync_source IS NOT NULL AND source_model_key IS NOT NULL;

CREATE INDEX idx_ai_models_sync_freshness
    ON ai_models (sync_source, source_last_seen_at DESC)
    WHERE sync_source IS NOT NULL;

CREATE TABLE model_sync_settings (
    id SMALLINT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT false,
    interval_minutes INTEGER NOT NULL DEFAULT 360,
    next_run_at TIMESTAMPTZ,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT model_sync_settings_singleton CHECK (id = 1),
    CONSTRAINT model_sync_settings_interval_valid CHECK (interval_minutes BETWEEN 15 AND 10080)
);

INSERT INTO model_sync_settings (id, enabled, interval_minutes, next_run_at)
VALUES (1, false, 360, NULL);

CREATE TABLE model_sync_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_type VARCHAR(24) NOT NULL,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL,
    upstream_total INTEGER,
    fetched_count INTEGER NOT NULL DEFAULT 0,
    inserted_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    unchanged_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_summary VARCHAR(256),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT model_sync_runs_trigger_valid CHECK (trigger_type IN ('manual', 'scheduled')),
    CONSTRAINT model_sync_runs_status_valid CHECK (status IN ('running', 'succeeded', 'failed')),
    CONSTRAINT model_sync_runs_counts_non_negative CHECK (
        fetched_count >= 0 AND inserted_count >= 0 AND updated_count >= 0
        AND unchanged_count >= 0 AND skipped_count >= 0
    )
);

CREATE INDEX idx_model_sync_runs_started_at ON model_sync_runs (started_at DESC);

COMMENT ON TABLE public.model_sync_settings IS '模型市场定时同步的管理员可控单例配置。';
COMMENT ON COLUMN public.model_sync_settings.id IS '固定为 1 的单例配置标识。';
COMMENT ON COLUMN public.model_sync_settings.enabled IS '是否允许调度器按周期自动执行模型市场同步。';
COMMENT ON COLUMN public.model_sync_settings.interval_minutes IS '自动同步周期，单位为分钟，允许范围为 15 分钟到 7 天。';
COMMENT ON COLUMN public.model_sync_settings.next_run_at IS '启用定时同步后的下一次计划执行时间；关闭时为空。';
COMMENT ON COLUMN public.model_sync_settings.updated_by IS '最近一次修改同步配置的管理员用户标识。';
COMMENT ON COLUMN public.model_sync_settings.version IS '乐观锁版本号，防止多个管理员互相覆盖同步配置。';
COMMENT ON COLUMN public.model_sync_settings.created_at IS '同步配置创建时间。';
COMMENT ON COLUMN public.model_sync_settings.updated_at IS '同步配置最后更新时间。';

COMMENT ON TABLE public.model_sync_runs IS '模型市场每次手动或定时同步的脱敏执行记录。';
COMMENT ON COLUMN public.model_sync_runs.id IS '同步执行记录全局唯一标识。';
COMMENT ON COLUMN public.model_sync_runs.trigger_type IS '触发方式：manual 管理员立即执行，scheduled 后台定时执行。';
COMMENT ON COLUMN public.model_sync_runs.actor_user_id IS '手动执行时的管理员标识；定时任务为空。';
COMMENT ON COLUMN public.model_sync_runs.status IS '执行状态：running、succeeded 或 failed。';
COMMENT ON COLUMN public.model_sync_runs.upstream_total IS '本轮上游声明的模型总数。';
COMMENT ON COLUMN public.model_sync_runs.fetched_count IS '本轮成功拉取并完成结构校验的模型数量。';
COMMENT ON COLUMN public.model_sync_runs.inserted_count IS '本轮安全新增到平台模型表的数量。';
COMMENT ON COLUMN public.model_sync_runs.updated_count IS '本轮上游快照或同步托管基础字段发生变化的数量。';
COMMENT ON COLUMN public.model_sync_runs.unchanged_count IS '本轮上游内容未发生变化、仅刷新最后发现时间的数量。';
COMMENT ON COLUMN public.model_sync_runs.skipped_count IS '因上游大小写冲突或不合法数据而跳过的数量。';
COMMENT ON COLUMN public.model_sync_runs.error_code IS '失败时的固定机器错误分类，不保存上游响应正文。';
COMMENT ON COLUMN public.model_sync_runs.error_summary IS '失败时的脱敏摘要，不包含 URL 参数、凭证或上游响应正文。';
COMMENT ON COLUMN public.model_sync_runs.started_at IS '本轮同步开始时间。';
COMMENT ON COLUMN public.model_sync_runs.completed_at IS '本轮同步完成或失败时间。';

COMMENT ON COLUMN public.ai_models.sync_source IS '模型同步来源标识；人工创建且未匹配上游时为空。';
COMMENT ON COLUMN public.ai_models.source_model_key IS '上游模型市场中的稳定模型键，当前使用原始 model_name。';
COMMENT ON COLUMN public.ai_models.source_managed IS '是否允许同步任务自动更新模型基础能力字段；人工编辑后自动关闭。';
COMMENT ON COLUMN public.ai_models.source_metadata IS '上游模型市场的白名单元数据快照，不包含本地售价、路由或凭证。';
COMMENT ON COLUMN public.ai_models.source_payload_hash IS '白名单上游快照的 SHA-256，用于识别内容是否变化。';
COMMENT ON COLUMN public.ai_models.source_last_seen_at IS '最近一次在完整上游快照中发现该模型的时间。';
COMMENT ON COLUMN public.ai_models.source_synced_at IS '最近一次写入上游快照或同步托管基础字段的时间。';
