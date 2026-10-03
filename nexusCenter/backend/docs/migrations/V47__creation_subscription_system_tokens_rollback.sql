-- V47 回滚：只移除本迁移新增的数据结构与带 seed_source=v47 标记的默认套餐。
BEGIN;

DELETE FROM plans
 WHERE entitlements ->> 'seed_source' = 'v47'
   AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.plan_id = plans.id);

DROP TABLE IF EXISTS system_access_tokens;

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_source_valid;
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_valid;
ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_status_valid
        CHECK (status IN ('pending', 'active', 'expired', 'cancelled', 'refunded'));
ALTER TABLE subscriptions DROP COLUMN IF EXISTS source;
ALTER TABLE subscriptions DROP COLUMN IF EXISTS version;

ALTER TABLE plans DROP COLUMN IF EXISTS description;
ALTER TABLE plans DROP COLUMN IF EXISTS display_order;
ALTER TABLE plans DROP COLUMN IF EXISTS featured;
ALTER TABLE plans DROP COLUMN IF EXISTS version;

COMMIT;
