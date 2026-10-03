-- 特殊服务分组按用户授权。普通分组继续由 routing_groups.audience = 'all' 直接开放。
CREATE TABLE routing_group_user_grants (
    group_id UUID NOT NULL REFERENCES routing_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    granted_by UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id),
    CONSTRAINT routing_group_user_grants_status_valid CHECK (status IN ('active', 'revoked'))
);

-- 用户侧目录和 API Key 鉴权按 user_id 查询当前有效授权。
CREATE INDEX idx_routing_group_user_grants_user_active
    ON routing_group_user_grants (user_id, group_id)
    WHERE status = 'active';

COMMENT ON TABLE routing_group_user_grants IS '特殊服务分组的用户授权关系；普通公开分组不写入本表';
COMMENT ON COLUMN routing_group_user_grants.group_id IS '需要明确授权使用的服务分组 ID';
COMMENT ON COLUMN routing_group_user_grants.user_id IS '获得该服务分组使用资格的用户 ID';
COMMENT ON COLUMN routing_group_user_grants.status IS '授权状态：active 有效，revoked 已撤销';
COMMENT ON COLUMN routing_group_user_grants.granted_by IS '最近一次授予该权限的管理员用户 ID';
COMMENT ON COLUMN routing_group_user_grants.expires_at IS '授权过期时间；为空表示长期有效';
COMMENT ON COLUMN routing_group_user_grants.created_at IS '首次创建授权关系的时间';
COMMENT ON COLUMN routing_group_user_grants.updated_at IS '最近一次授权或撤销时间';
