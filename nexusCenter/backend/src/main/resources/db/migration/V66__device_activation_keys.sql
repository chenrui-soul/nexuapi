-- Device activation keys are independent from API keys and system access tokens.
CREATE TABLE device_activation_keys (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    application_code VARCHAR(120) NOT NULL,
    key_prefix VARCHAR(32) NOT NULL,
    key_suffix VARCHAR(32) NOT NULL,
    encrypted_secret BYTEA NOT NULL,
    secret_hash BYTEA NOT NULL,
    device_code_hash BYTEA,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    activated_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    last_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT device_activation_keys_status_ck CHECK (status IN ('active','disabled','expired')),
    CONSTRAINT device_activation_keys_secret_hash_uk UNIQUE (secret_hash)
);
COMMENT ON TABLE device_activation_keys IS '独立设备激活密钥；用于应用激活，不参与API请求认证。';
COMMENT ON COLUMN device_activation_keys.encrypted_secret IS 'AES-GCM 加密后的设备密钥明文，仅管理员列表复制时解密。';
COMMENT ON COLUMN device_activation_keys.secret_hash IS '设备密钥 SHA-256 摘要，用于激活校验。';
COMMENT ON COLUMN device_activation_keys.application_code IS '使用该设备密钥的应用编码。';
COMMENT ON COLUMN device_activation_keys.status IS '管理状态：active、disabled 或 expired。';
COMMENT ON COLUMN device_activation_keys.expires_at IS '设备密钥失效时间，空值表示长期有效。';
COMMENT ON COLUMN device_activation_keys.device_code_hash IS '已绑定本地设备码的哈希；一枚密钥最多绑定一个设备。';
CREATE UNIQUE INDEX device_activation_keys_device_code_uk ON device_activation_keys(device_code_hash) WHERE device_code_hash IS NOT NULL;
CREATE INDEX device_activation_keys_status_idx ON device_activation_keys(status, expires_at);
