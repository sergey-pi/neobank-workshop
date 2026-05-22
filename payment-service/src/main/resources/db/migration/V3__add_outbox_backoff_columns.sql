-- V3: Add exponential back-off metadata to payment_outbox
--
-- last_attempted_at: timestamp of the most recent processing attempt (success or failure);
--                    useful for ops dashboards and alerting on stale events
-- next_retry_at    : earliest time the poller may pick this row up again after a failure;
--                    NULL = eligible immediately. The poller filters with:
--                    WHERE next_retry_at IS NULL OR next_retry_at <= NOW()
--                    Back-off formula: base_delay_seconds * 2^(retry_count - 1)
--                    e.g. attempt 1 → 30s, attempt 2 → 60s, attempt 3 → 120s → FAILED

ALTER TABLE payment_outbox
    ADD COLUMN last_attempted_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN next_retry_at      TIMESTAMP WITH TIME ZONE;

-- Partial index: keeps back-off filter fast; only rows with a future deadline are indexed
CREATE INDEX idx_payment_outbox_next_retry
    ON payment_outbox (next_retry_at)
    WHERE next_retry_at IS NOT NULL;
