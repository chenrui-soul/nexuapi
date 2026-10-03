-- 回滚 V46：删除密码最近变更时间字段。执行前确认没有后续安全策略依赖该字段。
ALTER TABLE users DROP COLUMN IF EXISTS password_changed_at;
