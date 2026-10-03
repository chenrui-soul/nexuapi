-- 路由分组可以为同一渠道配置独立凭证，满足不同性能分组使用不同上游 API Key 的场景。
-- 渠道默认凭证仍然保留，未配置分组覆盖时由 Gateway 自动回退使用。

CREATE TABLE routing_group_channel_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES routing_groups(id) ON DELETE CASCADE,
    channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    encrypted_credential BYTEA NOT NULL,
    credential_key_version INTEGER NOT NULL DEFAULT 1,
    credential_fingerprint VARCHAR(16) NOT NULL,
    credential_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT routing_group_channel_credentials_identity UNIQUE (group_id, channel_id),
    CONSTRAINT routing_group_channel_credentials_status_valid CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_routing_group_channel_credentials_lookup
    ON routing_group_channel_credentials (group_id, channel_id, status);

COMMENT ON TABLE public.routing_group_channel_credentials IS '路由分组对渠道默认凭证的安全覆盖配置，同一渠道可在不同分组使用不同上游 API Key。';
COMMENT ON COLUMN public.routing_group_channel_credentials.id IS '分组渠道凭证配置的全局唯一标识。';
COMMENT ON COLUMN public.routing_group_channel_credentials.group_id IS '使用该独立凭证的路由分组标识。';
COMMENT ON COLUMN public.routing_group_channel_credentials.channel_id IS '被覆盖默认凭证的上游渠道标识。';
COMMENT ON COLUMN public.routing_group_channel_credentials.encrypted_credential IS 'AES-256-GCM 加密后的上游凭证密文，禁止通过接口、日志或审计回显。';
COMMENT ON COLUMN public.routing_group_channel_credentials.credential_key_version IS '凭证加密密钥版本，用于后续安全轮换。';
COMMENT ON COLUMN public.routing_group_channel_credentials.credential_fingerprint IS '不可逆短指纹，只用于管理员核对密钥是否已轮换。';
COMMENT ON COLUMN public.routing_group_channel_credentials.credential_updated_at IS '最近一次写入或轮换独立凭证的时间。';
COMMENT ON COLUMN public.routing_group_channel_credentials.status IS 'active 使用分组独立凭证；disabled 回退渠道默认凭证。';
COMMENT ON COLUMN public.routing_group_channel_credentials.version IS '乐观锁版本号，防止并发轮换相互覆盖。';
COMMENT ON COLUMN public.routing_group_channel_credentials.created_at IS '配置创建时间。';
COMMENT ON COLUMN public.routing_group_channel_credentials.updated_at IS '配置最后更新时间。';
