ALTER TABLE api_keys
    ADD COLUMN key_hash_version SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT api_keys_hash_length_valid
        CHECK (octet_length(key_hash) = 32),
    ADD CONSTRAINT api_keys_credit_limit_positive
        CHECK (credit_limit IS NULL OR credit_limit > 0),
    ADD CONSTRAINT api_keys_expiry_after_creation
        CHECK (expires_at IS NULL OR expires_at > created_at);

CREATE INDEX idx_api_keys_active_expiry
    ON api_keys (expires_at)
    WHERE status = 'active' AND expires_at IS NOT NULL;
