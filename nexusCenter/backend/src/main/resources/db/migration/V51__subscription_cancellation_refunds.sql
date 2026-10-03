-- 订阅取消、积分失效与退款闭环。
-- 取消立即停止订阅权限；在途冻结完成收尾后，按未使用订阅积分比例执行退款。

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS order_id UUID REFERENCES orders(id),
    ADD COLUMN IF NOT EXISTS refund_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS refund_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS refund_amount NUMERIC(20, 8),
    ADD COLUMN IF NOT EXISTS refundable_credits NUMERIC(30, 12);

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_valid;
ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_status_valid
        CHECK (status IN ('pending', 'active', 'paused', 'expired', 'cancelled', 'refund_pending', 'refunded'));

CREATE INDEX IF NOT EXISTS idx_subscriptions_refund_pending
    ON subscriptions (status, refund_requested_at)
    WHERE status = 'refund_pending';

CREATE TABLE subscription_refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL UNIQUE REFERENCES subscriptions(id),
    user_id UUID NOT NULL REFERENCES users(id),
    order_id UUID REFERENCES orders(id),
    refund_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    refundable_credits NUMERIC(30, 12) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    provider_refund_id VARCHAR(160),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT subscription_refunds_status_valid
        CHECK (status IN ('pending', 'succeeded', 'failed')),
    CONSTRAINT subscription_refunds_amounts_valid
        CHECK (refund_amount >= 0 AND refundable_credits >= 0)
);

CREATE INDEX idx_subscription_refunds_user_time
    ON subscription_refunds (user_id, requested_at DESC);

COMMENT ON COLUMN subscriptions.order_id IS '订阅开通对应的支付订单；历史无订单订阅为空。';
COMMENT ON COLUMN subscriptions.refund_requested_at IS '用户发起订阅取消退款的时间。';
COMMENT ON COLUMN subscriptions.refund_completed_at IS '退款成功或模拟退款完成的时间。';
COMMENT ON COLUMN subscriptions.refund_amount IS '按未使用订阅积分比例计算并四舍五入到两位的小数退款金额。';
COMMENT ON COLUMN subscriptions.refundable_credits IS '退款完成时最终确认的未使用订阅积分。';
COMMENT ON TABLE subscription_refunds IS '订阅退款记录；以订阅唯一约束和幂等键防止重复退款。';
COMMENT ON COLUMN subscription_refunds.id IS '订阅退款记录全局唯一标识。';
COMMENT ON COLUMN subscription_refunds.subscription_id IS '被取消并申请退款的订阅标识。';
COMMENT ON COLUMN subscription_refunds.user_id IS '退款所属用户标识，所有查询必须按用户隔离。';
COMMENT ON COLUMN subscription_refunds.order_id IS '原订阅支付订单标识；历史无订单退款为空。';
COMMENT ON COLUMN subscription_refunds.refund_amount IS '本次应退法币金额，Mock 环境为模拟退款结果。';
COMMENT ON COLUMN subscription_refunds.refundable_credits IS '退款金额计算所依据的未使用订阅积分。';
COMMENT ON COLUMN subscription_refunds.status IS '退款状态：pending、succeeded 或 failed。';
COMMENT ON COLUMN subscription_refunds.idempotency_key IS '退款操作幂等键，重复请求只返回同一结果。';
COMMENT ON COLUMN subscription_refunds.provider_refund_id IS '支付渠道退款单号；Mock 或未接入渠道时为空。';
COMMENT ON COLUMN subscription_refunds.requested_at IS '退款申请时间。';
COMMENT ON COLUMN subscription_refunds.completed_at IS '退款完成时间。';
COMMENT ON COLUMN subscription_refunds.metadata IS '退款计算与执行的非敏感审计元数据。';

