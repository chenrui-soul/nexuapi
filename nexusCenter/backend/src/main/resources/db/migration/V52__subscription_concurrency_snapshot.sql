-- 套餐并发限制快照。
-- 订阅开通时固定当期并发上限，避免管理员后续修改套餐影响已售订阅。

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS concurrency_limit INTEGER;

UPDATE subscriptions s
   SET concurrency_limit = p.concurrency_limit
  FROM plans p
 WHERE p.id = s.plan_id
   AND s.concurrency_limit IS NULL;

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_concurrency_positive;
ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_concurrency_positive
        CHECK (concurrency_limit IS NULL OR concurrency_limit > 0);

COMMENT ON COLUMN subscriptions.concurrency_limit IS '订阅开通时从套餐复制的最大并发请求数；为空表示不由订阅限制。';
