-- 仅供人工回滚管理端用户与审计查询能力；执行前必须确认没有用户管理更新或后续迁移依赖 users.version。

DROP INDEX IF EXISTS idx_audit_logs_created_id;
DROP INDEX IF EXISTS idx_audit_logs_action_time;

ALTER TABLE users
    DROP COLUMN IF EXISTS version;
