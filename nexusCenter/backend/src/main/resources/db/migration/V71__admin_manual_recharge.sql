ALTER TABLE platform_settings ADD COLUMN manual_recharge_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE admin_recharge_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    actor_id UUID NOT NULL REFERENCES users(id),
    credits NUMERIC(30,12) NOT NULL CHECK (credits > 0),
    reason VARCHAR(200) NOT NULL,
    ledger_id UUID REFERENCES billing_ledger(id),
    available_after NUMERIC(30,12),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE admin_recharge_requests IS '管理员手动充值凭证，全局请求 ID 防止重复提交及跨用户重复入账。';
