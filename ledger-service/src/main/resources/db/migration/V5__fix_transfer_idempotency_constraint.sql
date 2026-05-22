-- Replace partial index with a standard UNIQUE constraint so that
-- ON CONFLICT (idempotency_key) DO NOTHING works correctly.
-- PostgreSQL allows multiple NULL values in a UNIQUE column (NULLs are never equal),
-- so transfers without an idempotency key are unaffected.
DROP INDEX IF EXISTS idx_transactions_idempotency_key;
ALTER TABLE transactions
    ADD CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key);
