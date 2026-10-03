ALTER TABLE users
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN public.users.version IS '用户管理配置乐观锁版本，每次管理员更新状态、名称或角色后递增。';

CREATE INDEX idx_audit_logs_action_time
    ON audit_logs (action, created_at DESC);

CREATE INDEX idx_audit_logs_created_id
    ON audit_logs (created_at DESC, id DESC);
