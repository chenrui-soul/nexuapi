ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS encrypted_secret bytea;
COMMENT ON COLUMN api_keys.encrypted_secret IS 'API 令牌完整密钥的 AES-GCM 加密密文；仅用于用户本人复制，不保存明文';
