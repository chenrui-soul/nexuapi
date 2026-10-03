-- V52 回滚脚本：删除订阅并发上限快照。
-- 执行前需确认当前没有依赖该快照的正式并发限制请求；建议优先恢复发布前数据库备份。

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_concurrency_positive;
ALTER TABLE subscriptions DROP COLUMN IF EXISTS concurrency_limit;
