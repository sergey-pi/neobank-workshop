-- Add idempotency key support to transfers.
-- A standard UNIQUE constraint (not a partial index) is required for
-- ON CONFLICT (idempotency_key) DO NOTHING to work correctly.
-- PostgreSQL allows multiple NULLs in a UNIQUE column (NULLs are never equal),
-- so transfers without an idempotency key are unaffected.
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(255);
ALTER TABLE transactions
    ADD CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key);
