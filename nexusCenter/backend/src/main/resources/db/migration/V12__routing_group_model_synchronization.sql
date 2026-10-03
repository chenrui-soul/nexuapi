-- 上游服务分组同步：保留上游分组溯源信息，并将“分组包含哪些模型”与具体渠道路由解耦。

ALTER TABLE routing_groups
    ADD COLUMN source_supplier_id UUID,
    ADD COLUMN source_group_id VARCHAR(160),
    ADD COLUMN source_group_name VARCHAR(120),
    ADD COLUMN sync_source VARCHAR(64),
    ADD COLUMN source_status VARCHAR(16),
    ADD COLUMN source_rate BIGINT,
    ADD COLUMN source_billing_type INTEGER,
    ADD COLUMN source_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN source_payload_hash CHAR(64),
    ADD COLUMN source_last_seen_at TIMESTAMPTZ,
    ADD COLUMN source_synced_at TIMESTAMPTZ,
    ADD COLUMN source_managed BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT fk_routing_groups_source_supplier
        FOREIGN KEY (source_supplier_id) REFERENCES suppliers(id) ON DELETE SET NULL,
    ADD CONSTRAINT routing_groups_source_status_valid
        CHECK (source_status IS NULL OR source_status IN ('active', 'stale')),
    ADD CONSTRAINT routing_groups_source_rate_non_negative
        CHECK (source_rate IS NULL OR source_rate >= 0),
    ADD CONSTRAINT routing_groups_source_metadata_object
        CHECK (jsonb_typeof(source_metadata) = 'object'),
    ADD CONSTRAINT routing_groups_source_identity_consistent
        CHECK (
            source_group_id IS NULL
            OR (source_supplier_id IS NOT NULL AND sync_source IS NOT NULL AND source_status IS NOT NULL)
        );

CREATE UNIQUE INDEX uk_routing_groups_source_identity
    ON routing_groups (source_supplier_id, source_group_id)
    WHERE source_supplier_id IS NOT NULL AND source_group_id IS NOT NULL;

CREATE INDEX idx_routing_groups_source_sync
    ON routing_groups (sync_source, source_status, source_last_seen_at DESC)
    WHERE sync_source IS NOT NULL;

CREATE TABLE routing_group_models (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES routing_groups(id) ON DELETE CASCADE,
    model_id UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    source_type VARCHAR(32) NOT NULL DEFAULT 'manual',
    source_status VARCHAR(16) NOT NULL DEFAULT 'active',
    upstream_last_status SMALLINT,
    upstream_success_rate INTEGER,
    upstream_consecutive_failures INTEGER NOT NULL DEFAULT 0,
    upstream_health_history JSONB NOT NULL DEFAULT '[]'::jsonb,
    upstream_last_checked_at TIMESTAMPTZ,
    upstream_last_success_at TIMESTAMPTZ,
    source_last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_routing_group_models_identity UNIQUE (group_id, model_id),
    CONSTRAINT routing_group_models_source_type_valid
        CHECK (source_type IN ('manual', 'caicai_market')),
    CONSTRAINT routing_group_models_source_status_valid
        CHECK (source_status IN ('active', 'stale')),
    CONSTRAINT routing_group_models_last_status_valid
        CHECK (upstream_last_status IS NULL OR upstream_last_status IN (0, 1)),
    CONSTRAINT routing_group_models_success_rate_valid
        CHECK (upstream_success_rate IS NULL OR upstream_success_rate BETWEEN 0 AND 10000),
    CONSTRAINT routing_group_models_failures_non_negative
        CHECK (upstream_consecutive_failures >= 0),
    CONSTRAINT routing_group_models_history_array
        CHECK (jsonb_typeof(upstream_health_history) = 'array')
);

CREATE INDEX idx_routing_group_models_group_status
    ON routing_group_models (group_id, source_status, model_id);

CREATE INDEX idx_routing_group_models_model_status
    ON routing_group_models (model_id, source_status, group_id);

-- 将历史路由回填为人工维护的分组模型关系，不改变现有 Gateway 路由行为。
INSERT INTO routing_group_models (id, group_id, model_id, source_type, source_status)
SELECT gen_random_uuid(), group_id, model_id, 'manual', 'active'
  FROM group_routes
 GROUP BY group_id, model_id
ON CONFLICT (group_id, model_id) DO NOTHING;

COMMENT ON TABLE public.routing_group_models IS '服务分组与平台模型的目录关联表，不代替具体渠道路由规则。';
COMMENT ON COLUMN public.routing_group_models.id IS '分组模型关联全局唯一标识。';
COMMENT ON COLUMN public.routing_group_models.group_id IS '所属服务分组标识。';
COMMENT ON COLUMN public.routing_group_models.model_id IS '分组包含的平台模型标识。';
COMMENT ON COLUMN public.routing_group_models.source_type IS '关联来源：manual 人工配置或 caicai_market 上游目录同步。';
COMMENT ON COLUMN public.routing_group_models.source_status IS '上游目录状态：active 本轮存在，stale 本轮已不可见；不会物理删除。';
COMMENT ON COLUMN public.routing_group_models.upstream_last_status IS '上游返回的最后健康状态原始值：0 异常或未检查，1 成功；缺失时为空。';
COMMENT ON COLUMN public.routing_group_models.upstream_success_rate IS '上游健康成功率原始整数，10000 表示 100%；不直接换算为售价。';
COMMENT ON COLUMN public.routing_group_models.upstream_consecutive_failures IS '上游记录的连续失败次数。';
COMMENT ON COLUMN public.routing_group_models.upstream_health_history IS '上游返回的脱敏健康历史数组，仅保存状态值。';
COMMENT ON COLUMN public.routing_group_models.upstream_last_checked_at IS '上游最近健康检查时间；原始秒级时间戳为 0 时为空。';
COMMENT ON COLUMN public.routing_group_models.upstream_last_success_at IS '上游最近健康成功时间；原始秒级时间戳为 0 时为空。';
COMMENT ON COLUMN public.routing_group_models.source_last_seen_at IS '最近一次在完整上游快照中发现该关联的时间。';
COMMENT ON COLUMN public.routing_group_models.created_at IS '分组模型关联创建时间。';
COMMENT ON COLUMN public.routing_group_models.updated_at IS '分组模型关联最后更新时间。';
COMMENT ON COLUMN public.routing_group_models.version IS '乐观锁版本号，预留给后续管理端人工启停关联。';

COMMENT ON COLUMN public.routing_groups.source_supplier_id IS '该上游服务分组所属的商业供应商标识。';
COMMENT ON COLUMN public.routing_groups.source_group_id IS '上游分组原始 ID，原样保留用于后续溯源和对账。';
COMMENT ON COLUMN public.routing_groups.source_group_name IS '上游最近一次返回的分组名称，不覆盖本地管理名称。';
COMMENT ON COLUMN public.routing_groups.sync_source IS '服务分组同步来源标识；人工分组为空。';
COMMENT ON COLUMN public.routing_groups.source_status IS '上游分组可见状态：active 本轮可见，stale 本轮不再返回；不自动改写本地 status。';
COMMENT ON COLUMN public.routing_groups.source_rate IS '上游成本倍率原始整数，例如 10000 通常表示 1 倍；未确认前不直接转换为本地售价倍率。';
COMMENT ON COLUMN public.routing_groups.source_billing_type IS '上游分组计费类型原始整数。';
COMMENT ON COLUMN public.routing_groups.source_metadata IS '上游分组的白名单元数据，不保存 Authorization、Token 或 API Key。';
COMMENT ON COLUMN public.routing_groups.source_payload_hash IS '上游分组白名单快照的 SHA-256，用于识别来源数据变化。';
COMMENT ON COLUMN public.routing_groups.source_last_seen_at IS '最近一次在完整上游分组快照中发现该分组的时间。';
COMMENT ON COLUMN public.routing_groups.source_synced_at IS '最近一次上游分组白名单快照发生变化的时间。';
COMMENT ON COLUMN public.routing_groups.source_managed IS '是否由同步任务创建或绑定来源身份；不表示允许覆盖本地售价、受众或状态。';
