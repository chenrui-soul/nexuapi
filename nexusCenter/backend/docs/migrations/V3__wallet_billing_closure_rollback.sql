DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM wallet_reservations)
       OR EXISTS (SELECT 1 FROM billing_refunds)
       OR EXISTS (SELECT 1 FROM billing_ledger WHERE amount = 0) THEN
        RAISE EXCEPTION 'V3 rollback requires an empty Wave 4 ledger/reservation dataset; export and reconcile funds first';
    END IF;
END;
$$;

DROP TRIGGER IF EXISTS trg_billing_ledger_no_delete ON billing_ledger;
DROP TRIGGER IF EXISTS trg_billing_ledger_no_update ON billing_ledger;
DROP FUNCTION IF EXISTS reject_billing_ledger_mutation();
DROP TRIGGER IF EXISTS trg_wallet_reservations_updated_at ON wallet_reservations;
ALTER TABLE billing_ledger DROP CONSTRAINT IF EXISTS billing_ledger_reservation_fk;
DROP TABLE IF EXISTS billing_refunds;
DROP TABLE IF EXISTS wallet_reservations;
ALTER TABLE billing_ledger
    DROP CONSTRAINT IF EXISTS billing_ledger_snapshot_consistent,
    DROP CONSTRAINT IF EXISTS billing_ledger_delta_consistent,
    DROP COLUMN IF EXISTS wallet_version_after,
    DROP COLUMN IF EXISTS available_after,
    DROP COLUMN IF EXISTS frozen_after,
    DROP COLUMN IF EXISTS expiring_after,
    DROP COLUMN IF EXISTS permanent_after,
    DROP COLUMN IF EXISTS frozen_delta,
    DROP COLUMN IF EXISTS expiring_delta,
    DROP COLUMN IF EXISTS permanent_delta,
    DROP COLUMN IF EXISTS reservation_id,
    DROP COLUMN IF EXISTS api_key_id,
    ADD CONSTRAINT billing_ledger_amount_nonzero CHECK (amount <> 0);
DROP INDEX IF EXISTS uk_billing_ledger_user_idempotency;
CREATE UNIQUE INDEX uk_billing_ledger_idempotency ON billing_ledger (idempotency_key);
