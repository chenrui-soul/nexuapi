-- V51 回滚脚本：仅适用于已确认没有依赖退款记录的隔离数据库或发布前备份恢复。
-- 正式库如已有 refund_pending/refunded 订阅，不允许直接执行，须先导出并人工处理退款对账。

DROP TABLE IF EXISTS subscription_refunds;
DROP INDEX IF EXISTS idx_subscriptions_refund_pending;

ALTER TABLE subscriptions
    DROP COLUMN IF EXISTS order_id,
    DROP COLUMN IF EXISTS refund_requested_at,
    DROP COLUMN IF EXISTS refund_completed_at,
    DROP COLUMN IF EXISTS refund_amount,
    DROP COLUMN IF EXISTS refundable_credits;

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_valid;
ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_status_valid
        CHECK (status IN ('pending', 'active', 'paused', 'expired', 'cancelled', 'refunded'));

