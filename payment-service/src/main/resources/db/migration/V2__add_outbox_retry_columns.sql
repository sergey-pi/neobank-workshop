-- V2: Add retry tracking columns to payment_outbox
--
-- retry_count: incremented on each failed processing attempt
-- error_message: stores the last exception message for observability
-- processed_at: timestamp when status flipped to PROCESSED

ALTER TABLE payment_outbox
    ADD COLUMN retry_count   INTEGER   NOT NULL DEFAULT 0,
    ADD COLUMN error_message TEXT,
    ADD COLUMN processed_at  TIMESTAMP WITH TIME ZONE;
