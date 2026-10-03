-- 创作空间、订阅计划与系统访问令牌第一阶段业务闭环。
-- 系统访问令牌与用户 API 令牌、上游 APIKey 完全分表，且只允许访问只读控制面接口。

ALTER TABLE plans
    ADD COLUMN IF NOT EXISTS description VARCHAR(500),
    ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS featured BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'admin',
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_valid;
ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_status_valid
        CHECK (status IN ('pending', 'active', 'paused', 'expired', 'cancelled', 'refunded'));

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_source_valid;
ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_source_valid
        CHECK (source IN ('admin', 'mock_payment', 'migration'));

CREATE TABLE system_access_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(120) NOT NULL,
    token_prefix VARCHAR(24) NOT NULL,
    token_suffix VARCHAR(12) NOT NULL,
    token_hash BYTEA NOT NULL,
    hash_version INTEGER NOT NULL,
    scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ip_allowlist JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT system_access_tokens_status_valid
        CHECK (status IN ('active', 'disabled', 'expired', 'revoked')),
    CONSTRAINT system_access_tokens_hash_version_valid CHECK (hash_version > 0),
    CONSTRAINT system_access_tokens_hash_length_valid CHECK (octet_length(token_hash) = 32),
    CONSTRAINT system_access_tokens_scopes_array CHECK (jsonb_typeof(scopes) = 'array'),
    CONSTRAINT system_access_tokens_ip_array CHECK (jsonb_typeof(ip_allowlist) = 'array'),
    CONSTRAINT system_access_tokens_expiry_valid CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE UNIQUE INDEX uk_system_access_tokens_hash
    ON system_access_tokens (hash_version, token_hash);
CREATE INDEX idx_system_access_tokens_user_status
    ON system_access_tokens (user_id, status, created_at DESC);
CREATE INDEX idx_system_access_tokens_active_expiry
    ON system_access_tokens (expires_at)
    WHERE status = 'active' AND expires_at IS NOT NULL;

CREATE TRIGGER trg_system_access_tokens_updated_at
    BEFORE UPDATE ON system_access_tokens
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO plans (
    code, name, description, billing_cycle, price, included_credits,
    concurrency_limit, entitlements, status, display_order, featured
) VALUES
    ('starter-monthly', '基础版', '适合轻量体验与个人创作', 'monthly', 59, 8000, 5,
     '{"features":["每月 8,000 积分","全模型标准队列","基础用量报表"],"creation_space":true,"api_key_limit":5,"seed_source":"v47"}'::jsonb,
     'active', 10, false),
    ('pro-monthly', '专业版', '适合开发者与持续创作', 'monthly', 199, 30000, 20,
     '{"features":["每月 30,000 积分","优先请求队列","完整调用分析"],"creation_space":true,"api_key_limit":20,"seed_source":"v47"}'::jsonb,
     'active', 20, true),
    ('team-monthly', '团队版', '适合稳定运行的业务团队', 'monthly', 699, 120000, 50,
     '{"features":["每月 120,000 积分","50 路并发请求","专属技术支持"],"creation_space":true,"api_key_limit":50,"seed_source":"v47"}'::jsonb,
     'active', 30, false)
ON CONFLICT (code) DO NOTHING;

COMMENT ON COLUMN plans.description IS '用户可见的套餐说明。';
COMMENT ON COLUMN plans.display_order IS '用户侧套餐列表排序值，数值越小越靠前。';
COMMENT ON COLUMN plans.featured IS '是否在用户侧标记为推荐套餐。';
COMMENT ON COLUMN plans.version IS '套餐乐观锁版本号，防止并发管理覆盖。';
COMMENT ON COLUMN subscriptions.source IS '订阅创建来源：admin、mock_payment 或 migration。';
COMMENT ON COLUMN subscriptions.version IS '订阅乐观锁版本号，防止并发状态修改。';

COMMENT ON TABLE system_access_tokens IS '系统访问令牌表，用于外部系统只读访问当前账户控制面数据；独立于用户 API 令牌和上游 APIKey。';
COMMENT ON COLUMN system_access_tokens.id IS '系统访问令牌记录全局唯一标识。';
COMMENT ON COLUMN system_access_tokens.user_id IS '令牌所属用户标识，所有查询必须按该字段隔离。';
COMMENT ON COLUMN system_access_tokens.name IS '用户为系统访问令牌设置的可读名称。';
COMMENT ON COLUMN system_access_tokens.token_prefix IS '可公开展示的令牌前缀，不包含完整凭证。';
COMMENT ON COLUMN system_access_tokens.token_suffix IS '可公开展示的令牌末尾字符，用于区分多枚令牌。';
COMMENT ON COLUMN system_access_tokens.token_hash IS '完整系统访问令牌的 HMAC-SHA256 摘要，数据库不保存明文。';
COMMENT ON COLUMN system_access_tokens.hash_version IS '生成 token_hash 时使用的 HMAC 密钥版本。';
COMMENT ON COLUMN system_access_tokens.scopes IS '只读权限范围 JSON 数组，例如 dashboard:read、wallet:read、logs:read。';
COMMENT ON COLUMN system_access_tokens.ip_allowlist IS '允许来源 IP 或 CIDR JSON 数组；空数组表示不限制来源。';
COMMENT ON COLUMN system_access_tokens.status IS '令牌状态：active、disabled、expired 或 revoked。';
COMMENT ON COLUMN system_access_tokens.expires_at IS '令牌到期时间；为空表示长期有效。';
COMMENT ON COLUMN system_access_tokens.last_used_at IS '最近一次成功访问只读系统接口的时间。';
COMMENT ON COLUMN system_access_tokens.revoked_at IS '令牌被永久撤销的时间；未撤销时为空。';
COMMENT ON COLUMN system_access_tokens.created_at IS '令牌创建时间。';
COMMENT ON COLUMN system_access_tokens.updated_at IS '令牌配置或状态最后更新时间。';
COMMENT ON COLUMN system_access_tokens.version IS '令牌乐观锁版本号，防止并发修改覆盖。';
