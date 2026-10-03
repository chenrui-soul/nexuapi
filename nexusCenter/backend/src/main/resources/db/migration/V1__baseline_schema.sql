CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name VARCHAR(80) NOT NULL,
    email_ciphertext BYTEA NOT NULL,
    email_lookup_hash BYTEA NOT NULL,
    email_key_version SMALLINT NOT NULL DEFAULT 1,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    email_verified_at TIMESTAMPTZ,
    avatar_url TEXT,
    registered_ip INET,
    registered_user_agent_hash VARCHAR(128),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT users_status_valid CHECK (status IN ('active', 'pending', 'suspended', 'locked', 'deleted'))
);

CREATE UNIQUE INDEX uk_users_email_lookup_active ON users (email_lookup_hash) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_status_created ON users (status, created_at DESC);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_code VARCHAR(32) NOT NULL,
    granted_by UUID REFERENCES users(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_code),
    CONSTRAINT user_roles_code_valid CHECK (role_code IN ('user', 'operator', 'admin'))
);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(100) NOT NULL,
    key_prefix VARCHAR(16) NOT NULL,
    key_suffix VARCHAR(8) NOT NULL,
    key_hash BYTEA NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    default_group_id UUID,
    allowed_model_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_group_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ip_allowlist JSONB NOT NULL DEFAULT '[]'::jsonb,
    rpm_limit INTEGER,
    tpm_limit BIGINT,
    concurrency_limit INTEGER,
    credit_limit NUMERIC(20, 8),
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT api_keys_status_valid CHECK (status IN ('active', 'disabled', 'expired', 'revoked')),
    CONSTRAINT api_keys_limits_positive CHECK (
        (rpm_limit IS NULL OR rpm_limit > 0) AND
        (tpm_limit IS NULL OR tpm_limit > 0) AND
        (concurrency_limit IS NULL OR concurrency_limit > 0)
    )
);

CREATE UNIQUE INDEX uk_api_keys_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_user_status ON api_keys (user_id, status);

CREATE TABLE ai_models (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_name VARCHAR(160) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    capability_type VARCHAR(24) NOT NULL,
    input_modalities JSONB NOT NULL DEFAULT '["text"]'::jsonb,
    output_modalities JSONB NOT NULL DEFAULT '["text"]'::jsonb,
    context_window BIGINT,
    max_output_tokens BIGINT,
    supports_streaming BOOLEAN NOT NULL DEFAULT false,
    supports_tools BOOLEAN NOT NULL DEFAULT false,
    supports_structured_output BOOLEAN NOT NULL DEFAULT false,
    input_price NUMERIC(20, 10) NOT NULL DEFAULT 0,
    output_price NUMERIC(20, 10) NOT NULL DEFAULT 0,
    cached_input_price NUMERIC(20, 10) NOT NULL DEFAULT 0,
    price_unit VARCHAR(32) NOT NULL DEFAULT 'million_tokens',
    public_visible BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ai_models_capability_valid CHECK (capability_type IN ('text', 'image', 'audio', 'video', 'embedding', 'multimodal')),
    CONSTRAINT ai_models_status_valid CHECK (status IN ('active', 'disabled', 'maintenance'))
);

CREATE INDEX idx_ai_models_catalog ON ai_models (public_visible, status, capability_type);

CREATE TABLE channels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL UNIQUE,
    provider_type VARCHAR(80) NOT NULL,
    base_url TEXT NOT NULL,
    encrypted_credential BYTEA NOT NULL,
    credential_key_version INTEGER NOT NULL DEFAULT 1,
    proxy_url TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'disabled',
    timeout_ms INTEGER NOT NULL DEFAULT 120000,
    concurrency_limit INTEGER,
    priority INTEGER NOT NULL DEFAULT 100,
    weight INTEGER NOT NULL DEFAULT 100,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    circuit_open_until TIMESTAMPTZ,
    last_error_summary VARCHAR(1000),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT channels_status_valid CHECK (status IN ('active', 'disabled', 'degraded', 'circuit_open')),
    CONSTRAINT channels_weight_positive CHECK (weight > 0),
    CONSTRAINT channels_timeout_positive CHECK (timeout_ms > 0)
);

CREATE INDEX idx_channels_routing ON channels (status, priority, weight);

CREATE TABLE channel_models (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    model_id UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    upstream_model VARCHAR(200) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 100,
    weight INTEGER NOT NULL DEFAULT 100,
    cost_input_price NUMERIC(20, 10) NOT NULL DEFAULT 0,
    cost_output_price NUMERIC(20, 10) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (channel_id, model_id, upstream_model),
    CONSTRAINT channel_models_status_valid CHECK (status IN ('active', 'disabled')),
    CONSTRAINT channel_models_weight_positive CHECK (weight > 0)
);

CREATE INDEX idx_channel_models_route ON channel_models (model_id, status, priority, weight);

CREATE TABLE routing_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    price_multiplier NUMERIC(12, 6) NOT NULL DEFAULT 1,
    audience VARCHAR(24) NOT NULL DEFAULT 'all',
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT routing_groups_multiplier_positive CHECK (price_multiplier > 0),
    CONSTRAINT routing_groups_audience_valid CHECK (audience IN ('all', 'assigned', 'internal')),
    CONSTRAINT routing_groups_status_valid CHECK (status IN ('active', 'disabled', 'degraded'))
);

ALTER TABLE api_keys
    ADD CONSTRAINT fk_api_keys_default_group FOREIGN KEY (default_group_id) REFERENCES routing_groups(id);

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
    UNIQUE (group_id, model_id, channel_model_id),
    CONSTRAINT group_routes_status_valid CHECK (status IN ('active', 'disabled')),
    CONSTRAINT group_routes_weight_positive CHECK (weight > 0)
);

CREATE INDEX idx_group_routes_select ON group_routes (group_id, model_id, status, priority, weight);

CREATE TABLE wallet_accounts (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    permanent_credits NUMERIC(20, 8) NOT NULL DEFAULT 0,
    expiring_credits NUMERIC(20, 8) NOT NULL DEFAULT 0,
    frozen_credits NUMERIC(20, 8) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT wallet_balances_nonnegative CHECK (
        permanent_credits >= 0 AND expiring_credits >= 0 AND frozen_credits >= 0
    )
);

CREATE TABLE billing_ledger (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    request_id VARCHAR(80),
    entry_type VARCHAR(32) NOT NULL,
    amount NUMERIC(20, 8) NOT NULL,
    balance_after NUMERIC(20, 8) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    source_type VARCHAR(64),
    source_id VARCHAR(160),
    expires_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT billing_ledger_amount_nonzero CHECK (amount <> 0),
    CONSTRAINT billing_ledger_type_valid CHECK (entry_type IN ('recharge', 'grant', 'reserve', 'settle', 'release', 'refund', 'expire', 'adjustment'))
);

CREATE UNIQUE INDEX uk_billing_ledger_idempotency ON billing_ledger (idempotency_key);
CREATE INDEX idx_billing_ledger_user_time ON billing_ledger (user_id, created_at DESC);
CREATE INDEX idx_billing_ledger_request ON billing_ledger (request_id) WHERE request_id IS NOT NULL;

CREATE TABLE plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    billing_cycle VARCHAR(24) NOT NULL,
    price NUMERIC(20, 8) NOT NULL,
    included_credits NUMERIC(20, 8) NOT NULL DEFAULT 0,
    concurrency_limit INTEGER,
    entitlements JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT plans_cycle_valid CHECK (billing_cycle IN ('monthly', 'quarterly', 'yearly', 'one_time')),
    CONSTRAINT plans_status_valid CHECK (status IN ('draft', 'active', 'archived'))
);

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    plan_id UUID NOT NULL REFERENCES plans(id),
    status VARCHAR(24) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    auto_renew BOOLEAN NOT NULL DEFAULT false,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT subscriptions_status_valid CHECK (status IN ('pending', 'active', 'expired', 'cancelled', 'refunded')),
    CONSTRAINT subscriptions_period_valid CHECK (expires_at > starts_at)
);

CREATE UNIQUE INDEX uk_subscriptions_one_active ON subscriptions (user_id) WHERE status = 'active';

CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    order_no VARCHAR(64) NOT NULL UNIQUE,
    order_type VARCHAR(24) NOT NULL,
    amount NUMERIC(20, 8) NOT NULL,
    currency VARCHAR(12) NOT NULL DEFAULT 'CNY',
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    payment_provider VARCHAR(64),
    provider_order_id VARCHAR(160),
    idempotency_key VARCHAR(160) NOT NULL,
    paid_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT orders_type_valid CHECK (order_type IN ('recharge', 'subscription')),
    CONSTRAINT orders_status_valid CHECK (status IN ('pending', 'paid', 'failed', 'cancelled', 'refunded')),
    CONSTRAINT orders_amount_positive CHECK (amount > 0)
);

CREATE UNIQUE INDEX uk_orders_idempotency ON orders (idempotency_key);

CREATE TABLE request_logs (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    request_id VARCHAR(80) NOT NULL,
    user_id UUID REFERENCES users(id),
    api_key_id UUID REFERENCES api_keys(id),
    model_id UUID REFERENCES ai_models(id),
    channel_id UUID REFERENCES channels(id),
    group_id UUID REFERENCES routing_groups(id),
    public_model VARCHAR(160) NOT NULL,
    upstream_model VARCHAR(200),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    status_code INTEGER,
    platform_error_code VARCHAR(64),
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cached_tokens BIGINT NOT NULL DEFAULT 0,
    billed_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    price_multiplier NUMERIC(12, 6) NOT NULL DEFAULT 1,
    streaming BOOLEAN NOT NULL DEFAULT false,
    retry_count INTEGER NOT NULL DEFAULT 0,
    client_ip INET,
    user_agent_hash VARCHAR(128),
    upstream_error_summary VARCHAR(1000),
    route_switch_reason VARCHAR(500),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE request_logs_default PARTITION OF request_logs DEFAULT;
CREATE INDEX idx_request_logs_user_time ON request_logs (user_id, created_at DESC);
CREATE INDEX idx_request_logs_request_id ON request_logs (request_id);
CREATE INDEX idx_request_logs_key_time ON request_logs (api_key_id, created_at DESC);
CREATE INDEX idx_request_logs_model_time ON request_logs (model_id, created_at DESC);

CREATE TABLE usage_aggregates (
    id BIGSERIAL PRIMARY KEY,
    bucket_start TIMESTAMPTZ NOT NULL,
    bucket_size VARCHAR(16) NOT NULL,
    dimension_type VARCHAR(32) NOT NULL,
    dimension_id VARCHAR(160) NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    billed_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    latency_sum_ms BIGINT NOT NULL DEFAULT 0,
    latency_p95_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (bucket_start, bucket_size, dimension_type, dimension_id),
    CONSTRAINT usage_aggregates_bucket_valid CHECK (bucket_size IN ('minute', 'hour', 'day'))
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    action_url TEXT,
    read_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user_unread ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;

CREATE TABLE health_checks (
    id BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL,
    target_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    latency_ms INTEGER,
    error_summary VARCHAR(1000),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT health_checks_target_valid CHECK (target_type IN ('channel', 'group')),
    CONSTRAINT health_checks_status_valid CHECK (status IN ('healthy', 'degraded', 'unavailable', 'unconfigured'))
);

CREATE INDEX idx_health_checks_target_time ON health_checks (target_type, target_id, checked_at DESC);

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id UUID REFERENCES users(id),
    actor_type VARCHAR(24) NOT NULL DEFAULT 'user',
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(160),
    before_data JSONB,
    after_data JSONB,
    ip_address INET,
    user_agent_hash VARCHAR(128),
    request_id VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_actor_time ON audit_logs (actor_user_id, created_at DESC);
CREATE INDEX idx_audit_logs_resource_time ON audit_logs (resource_type, resource_id, created_at DESC);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT outbox_events_status_valid CHECK (status IN ('pending', 'processing', 'published', 'failed'))
);

CREATE INDEX idx_outbox_events_delivery ON outbox_events (status, available_at) WHERE status IN ('pending', 'failed');

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_api_keys_updated_at BEFORE UPDATE ON api_keys
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_ai_models_updated_at BEFORE UPDATE ON ai_models
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_channels_updated_at BEFORE UPDATE ON channels
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_channel_models_updated_at BEFORE UPDATE ON channel_models
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_routing_groups_updated_at BEFORE UPDATE ON routing_groups
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_group_routes_updated_at BEFORE UPDATE ON group_routes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_wallet_accounts_updated_at BEFORE UPDATE ON wallet_accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_plans_updated_at BEFORE UPDATE ON plans
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_subscriptions_updated_at BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_orders_updated_at BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_usage_aggregates_updated_at BEFORE UPDATE ON usage_aggregates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
