-- V3: Performance index for daily spend queries
-- Supports: SUM(ABS(amount)) WHERE account_id=? AND type='DEBIT' AND created_at >= today

CREATE INDEX idx_entries_account_daily ON entries (account_id, type, created_at);
