-- V49 订阅积分批次回滚脚本。
-- 执行前必须确认没有 V49 之后的应用仍在运行；聚合钱包余额会保留，但批次来源审计会被删除。

BEGIN;

DROP TRIGGER IF EXISTS trg_wallet_reservation_batch_allocations_updated_at
    ON wallet_reservation_batch_allocations;
DROP TRIGGER IF EXISTS trg_expiring_credit_batches_updated_at
    ON expiring_credit_batches;

DROP TABLE IF EXISTS wallet_reservation_batch_allocations;

ALTER TABLE wallet_reservations
    DROP COLUMN IF EXISTS service_group_id;

DROP TABLE IF EXISTS expiring_credit_batches;
DROP TABLE IF EXISTS subscription_service_groups;
DROP TABLE IF EXISTS plan_service_groups;

DELETE FROM flyway_schema_history WHERE version = '49';

COMMIT;
