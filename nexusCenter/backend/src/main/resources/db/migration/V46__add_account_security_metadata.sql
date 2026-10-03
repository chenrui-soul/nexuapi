-- 记录密码最近变更时间，供用户侧账户安全页面展示并支持后续安全策略判断。
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;

-- 历史账号以创建时间作为首次密码设置时间，避免账户安全页面出现不确定状态。
UPDATE users SET password_changed_at = created_at WHERE password_changed_at IS NULL;

ALTER TABLE users
    ALTER COLUMN password_changed_at SET DEFAULT now(),
    ALTER COLUMN password_changed_at SET NOT NULL;

COMMENT ON COLUMN users.password_changed_at IS '密码最近设置或修改时间；注册、找回密码和登录态修改密码时更新';
