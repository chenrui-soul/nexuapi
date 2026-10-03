-- 仅在回滚到仍依赖 group_routes 的旧应用版本前执行。
-- 本脚本根据当前分组模型、供应商关系、渠道及渠道模型数据重建兼容路由，不恢复已经删除的历史人工状态。

CREATE TABLE group_routes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES routing_groups(id) ON DELETE CASCADE,
    model_id UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    channel_model_id UUID NOT NULL REFERENCES channel_models(id) ON DELETE CASCADE,
    priority INTEGER NOT NULL DEFAULT 100,
    weight INTEGER NOT NULL DEFAULT 100,
    retryable BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_group_routes_identity UNIQUE (group_id, model_id, channel_model_id),
    CONSTRAINT group_routes_status_valid CHECK (status IN ('active', 'disabled')),
    CONSTRAINT group_routes_weight_positive CHECK (weight > 0),
    CONSTRAINT group_routes_priority_non_negative CHECK (priority >= 0)
);

CREATE INDEX idx_group_routes_select
    ON group_routes (group_id, model_id, status, priority, weight);

CREATE TRIGGER trg_group_routes_updated_at
BEFORE UPDATE ON group_routes
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO group_routes (
    id, group_id, model_id, channel_model_id, priority, weight, retryable, status
)
SELECT gen_random_uuid(), rgm.group_id, rgm.model_id, cm.id,
       LEAST(2147483647, rgs.priority::bigint + c.priority::bigint + cm.priority::bigint)::integer,
       GREATEST(1, LEAST(2147483647, rgs.weight::bigint * c.weight::bigint * cm.weight::bigint))::integer,
       true, 'active'
  FROM routing_group_models rgm
  JOIN ai_models m ON m.id = rgm.model_id
  JOIN routing_group_suppliers rgs ON rgs.group_id = rgm.group_id AND rgs.status = 'active'
  JOIN channels c ON c.supplier_id = rgs.supplier_id
  JOIN channel_models cm
    ON cm.channel_id = c.id AND cm.model_id = rgm.model_id AND cm.status = 'active'
 WHERE rgm.source_status = 'active'
   AND (
        c.endpoint_type = 'multimodal'
        OR c.endpoint_type = m.capability_type
        OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text')
   )
ON CONFLICT (group_id, model_id, channel_model_id) DO NOTHING;

COMMENT ON TABLE public.group_routes IS '旧版本应用使用的服务分组模型到渠道模型兼容路由表';
COMMENT ON COLUMN public.group_routes.id IS '兼容路由全局唯一标识';
COMMENT ON COLUMN public.group_routes.group_id IS '兼容路由所属服务分组标识';
COMMENT ON COLUMN public.group_routes.model_id IS '客户端请求的平台模型标识';
COMMENT ON COLUMN public.group_routes.channel_model_id IS '旧版本实际选择的渠道模型映射标识';
COMMENT ON COLUMN public.group_routes.priority IS '由分组供应商、渠道和渠道模型优先级合成的兼容值';
COMMENT ON COLUMN public.group_routes.weight IS '由分组供应商、渠道和渠道模型权重合成的兼容值';
COMMENT ON COLUMN public.group_routes.retryable IS '首个响应输出前是否允许切换候选重试';
COMMENT ON COLUMN public.group_routes.status IS '兼容路由状态：active 或 disabled';
COMMENT ON COLUMN public.group_routes.created_at IS '兼容路由创建时间';
COMMENT ON COLUMN public.group_routes.updated_at IS '兼容路由最后更新时间';
COMMENT ON COLUMN public.group_routes.version IS '兼容路由乐观锁版本号';
