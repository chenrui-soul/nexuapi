DROP INDEX IF EXISTS idx_api_keys_active_expiry;

ALTER TABLE api_keys
    DROP CONSTRAINT IF EXISTS api_keys_expiry_after_creation,
    DROP CONSTRAINT IF EXISTS api_keys_credit_limit_positive,
    DROP CONSTRAINT IF EXISTS api_keys_hash_length_valid,
    DROP COLUMN IF EXISTS status_changed_at,
    DROP COLUMN IF EXISTS version,
    DROP COLUMN IF EXISTS key_hash_version;
