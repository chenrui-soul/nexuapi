-- Wave 4 资金闭环：保留 V1 的账户与账本表，通过增量结构补齐冻结、结算和退款语义。

DROP INDEX IF EXISTS uk_billing_ledger_idempotency;
CREATE UNIQUE INDEX uk_billing_ledger_user_idempotency
    ON billing_ledger (user_id, idempotency_key);

ALTER TABLE billing_ledger
    DROP CONSTRAINT IF EXISTS billing_ledger_amount_nonzero,
    ADD COLUMN api_key_id UUID REFERENCES api_keys(id),
    ADD COLUMN reservation_id UUID,
    ADD COLUMN permanent_delta NUMERIC(20, 8),
    ADD COLUMN expiring_delta NUMERIC(20, 8),
    ADD COLUMN frozen_delta NUMERIC(20, 8),
    ADD COLUMN permanent_after NUMERIC(20, 8),
    ADD COLUMN expiring_after NUMERIC(20, 8),
    ADD COLUMN frozen_after NUMERIC(20, 8),
    ADD COLUMN available_after NUMERIC(20, 8),
    ADD COLUMN wallet_version_after BIGINT;

-- 兼容可能已经存在的 V1 账本：旧 amount 视为永久余额变化。
UPDATE billing_ledger
   SET permanent_delta = amount,
       expiring_delta = 0,
       frozen_delta = 0,
       permanent_after = balance_after,
       expiring_after = 0,
       frozen_after = 0,
       available_after = balance_after,
       wallet_version_after = 0
 WHERE permanent_delta IS NULL;

ALTER TABLE billing_ledger
    ALTER COLUMN permanent_delta SET NOT NULL,
    ALTER COLUMN expiring_delta SET NOT NULL,
    ALTER COLUMN frozen_delta SET NOT NULL,
    ALTER COLUMN permanent_after SET NOT NULL,
    ALTER COLUMN expiring_after SET NOT NULL,
    ALTER COLUMN frozen_after SET NOT NULL,
    ALTER COLUMN available_after SET NOT NULL,
    ALTER COLUMN wallet_version_after SET NOT NULL,
    ADD CONSTRAINT billing_ledger_delta_consistent CHECK (
        amount = permanent_delta + expiring_delta + frozen_delta
    ),
    ADD CONSTRAINT billing_ledger_snapshot_consistent CHECK (
        balance_after = permanent_after + expiring_after + frozen_after
        AND available_after = permanent_after + expiring_after
        AND permanent_after >= 0
        AND expiring_after >= 0
        AND frozen_after >= 0
    );

CREATE TABLE wallet_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    api_key_id UUID REFERENCES api_keys(id),
    request_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'reserved',
    reserved_amount NUMERIC(20, 8) NOT NULL,
    reserved_permanent NUMERIC(20, 8) NOT NULL DEFAULT 0,
    reserved_expiring NUMERIC(20, 8) NOT NULL DEFAULT 0,
    settled_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    settled_permanent NUMERIC(20, 8) NOT NULL DEFAULT 0,
    settled_expiring NUMERIC(20, 8) NOT NULL DEFAULT 0,
    refunded_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    refunded_permanent NUMERIC(20, 8) NOT NULL DEFAULT 0,
    refunded_expiring NUMERIC(20, 8) NOT NULL DEFAULT 0,
    settlement_idempotency_key VARCHAR(160),
    release_idempotency_key VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT wallet_reservations_status_valid CHECK (status IN ('reserved', 'settled', 'released')),
    CONSTRAINT wallet_reservations_amounts_valid CHECK (
        reserved_amount > 0
        AND reserved_permanent >= 0
        AND reserved_expiring >= 0
        AND reserved_amount = reserved_permanent + reserved_expiring
        AND settled_amount >= 0
        AND settled_permanent >= 0
        AND settled_expiring >= 0
        AND settled_amount = settled_permanent + settled_expiring
        AND refunded_amount >= 0
        AND refunded_permanent >= 0
        AND refunded_expiring >= 0
        AND refunded_amount = refunded_permanent + refunded_expiring
        AND refunded_amount <= settled_amount
    ),
    UNIQUE (user_id, idempotency_key),
    UNIQUE (user_id, request_id)
);

CREATE UNIQUE INDEX uk_wallet_reservations_settlement_key
    ON wallet_reservations (user_id, settlement_idempotency_key)
    WHERE settlement_idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uk_wallet_reservations_release_key
    ON wallet_reservations (user_id, release_idempotency_key)
    WHERE release_idempotency_key IS NOT NULL;
CREATE INDEX idx_wallet_reservations_user_time
    ON wallet_reservations (user_id, created_at DESC);
CREATE INDEX idx_wallet_reservations_api_key_status
    ON wallet_reservations (api_key_id, status)
    WHERE api_key_id IS NOT NULL;

ALTER TABLE billing_ledger
    ADD CONSTRAINT billing_ledger_reservation_fk
        FOREIGN KEY (reservation_id) REFERENCES wallet_reservations(id);

CREATE TABLE billing_refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    reservation_id UUID NOT NULL REFERENCES wallet_reservations(id),
    idempotency_key VARCHAR(160) NOT NULL,
    amount NUMERIC(20, 8) NOT NULL,
    permanent_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    expiring_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    cumulative_amount NUMERIC(20, 8) NOT NULL,
    refundable_after NUMERIC(20, 8) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT billing_refunds_amount_valid CHECK (
        amount > 0
        AND permanent_amount >= 0
        AND expiring_amount >= 0
        AND amount = permanent_amount + expiring_amount
        AND cumulative_amount >= amount
        AND refundable_after >= 0
    ),
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_billing_refunds_reservation_time
    ON billing_refunds (reservation_id, created_at DESC);

CREATE TRIGGER trg_wallet_reservations_updated_at BEFORE UPDATE ON wallet_reservations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 账本是审计真相源，一旦写入只能追加，不允许任何业务代码修改或删除历史记录。
CREATE OR REPLACE FUNCTION reject_billing_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'billing_ledger is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_billing_ledger_no_update
    BEFORE UPDATE ON billing_ledger
    FOR EACH ROW EXECUTE FUNCTION reject_billing_ledger_mutation();
CREATE TRIGGER trg_billing_ledger_no_delete
    BEFORE DELETE ON billing_ledger
    FOR EACH ROW EXECUTE FUNCTION reject_billing_ledger_mutation();
