-- 订阅套餐分组权限与积分批次计费闭环。
-- 聚合钱包继续作为快速余额快照，批次与冻结分摊记录作为限时积分资金来源真相。

CREATE TABLE plan_service_groups (
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    service_group_id UUID NOT NULL REFERENCES routing_groups(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plan_id, service_group_id)
);

CREATE INDEX idx_plan_service_groups_group
    ON plan_service_groups (service_group_id, plan_id);

CREATE TABLE subscription_service_groups (
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    service_group_id UUID NOT NULL REFERENCES routing_groups(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (subscription_id, service_group_id)
);

CREATE INDEX idx_subscription_service_groups_group
    ON subscription_service_groups (service_group_id, subscription_id);

CREATE TABLE expiring_credit_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    subscription_id UUID REFERENCES subscriptions(id),
    credit_ledger_id UUID REFERENCES billing_ledger(id),
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    granted_amount NUMERIC(30, 12) NOT NULL,
    available_amount NUMERIC(30, 12) NOT NULL,
    frozen_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    consumed_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    expired_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT expiring_credit_batches_status_valid
        CHECK (status IN ('active', 'depleted', 'expired')),
    CONSTRAINT expiring_credit_batches_amounts_valid CHECK (
        granted_amount > 0
        AND available_amount >= 0
        AND frozen_amount >= 0
        AND consumed_amount >= 0
        AND expired_amount >= 0
        AND granted_amount = available_amount + frozen_amount + consumed_amount + expired_amount
    ),
    CONSTRAINT expiring_credit_batches_expiry_valid CHECK (expires_at > created_at),
    UNIQUE (user_id, source_type, source_id)
);

CREATE UNIQUE INDEX uk_expiring_credit_batches_ledger
    ON expiring_credit_batches (credit_ledger_id)
    WHERE credit_ledger_id IS NOT NULL;
CREATE INDEX idx_expiring_credit_batches_user_spend
    ON expiring_credit_batches (user_id, expires_at, created_at, id)
    WHERE available_amount > 0;
CREATE INDEX idx_expiring_credit_batches_subscription
    ON expiring_credit_batches (subscription_id, expires_at, id)
    WHERE subscription_id IS NOT NULL;
CREATE INDEX idx_expiring_credit_batches_due
    ON expiring_credit_batches (expires_at, user_id)
    WHERE available_amount > 0;

ALTER TABLE wallet_reservations
    ADD COLUMN service_group_id UUID REFERENCES routing_groups(id);

CREATE TABLE wallet_reservation_batch_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID NOT NULL REFERENCES wallet_reservations(id) ON DELETE CASCADE,
    batch_id UUID NOT NULL REFERENCES expiring_credit_batches(id),
    reserved_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    additional_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    settled_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    released_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    expired_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    refunded_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT wallet_reservation_batch_allocations_amounts_valid CHECK (
        reserved_amount >= 0
        AND additional_amount >= 0
        AND settled_amount >= 0
        AND released_amount >= 0
        AND expired_amount >= 0
        AND refunded_amount >= 0
        AND additional_amount <= settled_amount
        AND refunded_amount <= settled_amount
        AND settled_amount + released_amount + expired_amount <= reserved_amount + additional_amount
    ),
    UNIQUE (reservation_id, batch_id)
);

CREATE INDEX idx_wallet_reservation_batch_allocations_batch
    ON wallet_reservation_batch_allocations (batch_id, reservation_id);

CREATE TRIGGER trg_expiring_credit_batches_updated_at
    BEFORE UPDATE ON expiring_credit_batches
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_wallet_reservation_batch_allocations_updated_at
    BEFORE UPDATE ON wallet_reservation_batch_allocations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 既有套餐先关联当前已开放的用户服务分组，避免升级后已有套餐变成不可用配置。
INSERT INTO plan_service_groups (plan_id, service_group_id)
SELECT p.id, g.id
  FROM plans p
 CROSS JOIN routing_groups g
 WHERE p.status = 'active'
   AND g.status = 'active'
   AND g.audience != 'internal'
ON CONFLICT DO NOTHING;

-- 已存在的活动订阅复制套餐分组关系，后续管理员修改套餐不会追溯改变已售订阅。
INSERT INTO subscription_service_groups (subscription_id, service_group_id)
SELECT s.id, psg.service_group_id
  FROM subscriptions s
  JOIN plan_service_groups psg ON psg.plan_id = s.plan_id
 WHERE s.status = 'active'
ON CONFLICT DO NOTHING;

-- 兼容升级前可能存在的聚合限时积分；无法还原批次时建立长期 legacy 批次，保证余额不丢失。
INSERT INTO expiring_credit_batches (
    id, user_id, subscription_id, credit_ledger_id, source_type, source_id,
    granted_amount, available_amount, frozen_amount, consumed_amount, expired_amount,
    expires_at, status
)
SELECT gen_random_uuid(), w.user_id, NULL, NULL, 'legacy_wallet', w.user_id::text,
       w.expiring_credits, w.expiring_credits, 0, 0, 0,
       COALESCE((
           SELECT max(l.expires_at)
             FROM billing_ledger l
            WHERE l.user_id = w.user_id
              AND l.expires_at > now()
       ), now() + interval '100 years'),
       'active'
  FROM wallet_accounts w
 WHERE w.expiring_credits > 0
ON CONFLICT DO NOTHING;

COMMENT ON TABLE plan_service_groups IS '订阅套餐允许使用的服务分组配置；一个套餐可关联多个服务分组。';
COMMENT ON COLUMN plan_service_groups.plan_id IS '订阅套餐标识。';
COMMENT ON COLUMN plan_service_groups.service_group_id IS '套餐允许用户使用的服务分组标识。';
COMMENT ON COLUMN plan_service_groups.created_at IS '套餐与服务分组关系创建时间。';

COMMENT ON TABLE subscription_service_groups IS '用户开通订阅时复制的服务分组权限快照，套餐后续修改不追溯影响已售订阅。';
COMMENT ON COLUMN subscription_service_groups.subscription_id IS '用户订阅记录标识。';
COMMENT ON COLUMN subscription_service_groups.service_group_id IS '该订阅周期允许使用的服务分组标识。';
COMMENT ON COLUMN subscription_service_groups.created_at IS '订阅权限快照创建时间。';

COMMENT ON TABLE expiring_credit_batches IS '限时积分批次真相表，记录每次发放后的可用、冻结、消费和过期归属。';
COMMENT ON COLUMN expiring_credit_batches.id IS '限时积分批次全局唯一标识。';
COMMENT ON COLUMN expiring_credit_batches.user_id IS '积分批次所属用户标识。';
COMMENT ON COLUMN expiring_credit_batches.subscription_id IS '积分来自订阅套餐时关联的订阅记录；通用限时积分为空。';
COMMENT ON COLUMN expiring_credit_batches.credit_ledger_id IS '创建该批次的入账流水标识；升级前兼容批次可为空。';
COMMENT ON COLUMN expiring_credit_batches.source_type IS '积分来源类型，例如 subscription、grant 或 legacy_wallet。';
COMMENT ON COLUMN expiring_credit_batches.source_id IS '积分来源业务记录标识，与来源类型共同保证用户内幂等。';
COMMENT ON COLUMN expiring_credit_batches.granted_amount IS '该批次最初发放的积分总额。';
COMMENT ON COLUMN expiring_credit_batches.available_amount IS '该批次当前可用于新请求冻结的积分。';
COMMENT ON COLUMN expiring_credit_batches.frozen_amount IS '该批次已被在途请求冻结的积分。';
COMMENT ON COLUMN expiring_credit_batches.consumed_amount IS '该批次已结算且尚未退款的净消费积分。';
COMMENT ON COLUMN expiring_credit_batches.expired_amount IS '该批次因到期失效且不可再次消费的积分。';
COMMENT ON COLUMN expiring_credit_batches.expires_at IS '该批次停止参与新请求冻结的到期时间。';
COMMENT ON COLUMN expiring_credit_batches.status IS '批次状态：active、depleted 或 expired。';
COMMENT ON COLUMN expiring_credit_batches.created_at IS '积分批次创建时间。';
COMMENT ON COLUMN expiring_credit_batches.updated_at IS '积分批次金额或状态最后更新时间。';
COMMENT ON COLUMN expiring_credit_batches.version IS '批次乐观锁版本号，防止并发分摊覆盖。';

COMMENT ON COLUMN wallet_reservations.service_group_id IS '请求实际使用的服务分组标识，用于订阅权限审计和结算差额资金选择。';

COMMENT ON TABLE wallet_reservation_batch_allocations IS '请求冻结单与限时积分批次的分摊记录，用于结算、释放、到期和退款原路处理。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.id IS '冻结分摊记录全局唯一标识。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.reservation_id IS '关联的钱包冻结单标识。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.batch_id IS '本次资金来自的限时积分批次标识。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.reserved_amount IS '请求发往上游前从该批次预冻结的积分。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.additional_amount IS '结算金额超过预冻结时从该批次额外直接结算的积分。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.settled_amount IS '从该批次确认结算的累计积分。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.released_amount IS '在批次有效期内释放回可用余额的累计积分。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.expired_amount IS '释放或结算时因批次已到期而直接失效的累计积分。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.refunded_amount IS '已按原资金来源退回该批次的累计积分。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.created_at IS '冻结分摊记录创建时间。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.updated_at IS '冻结分摊金额最后更新时间。';
COMMENT ON COLUMN wallet_reservation_batch_allocations.version IS '冻结分摊乐观锁版本号。';

