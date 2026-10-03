ALTER TABLE device_activation_keys DROP CONSTRAINT device_activation_keys_status_ck;
ALTER TABLE device_activation_keys ADD CONSTRAINT device_activation_keys_status_ck CHECK (status IN ('active', 'disabled', 'expired', 'deleted'));
COMMENT ON COLUMN device_activation_keys.status IS '管理状态：active、disabled、expired 或 deleted；deleted 为软删除且不可再激活。';
