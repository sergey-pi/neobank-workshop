-- Add idempotency key support to transfers.
-- A unique constraint ensures duplicate requests with the same key cannot insert
-- twice even under concurrent load (INSERT ON CONFLICT DO NOTHING guard).
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(255);
CREATE UNIQUE INDEX idx_transactions_idempotency_key
    ON transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
