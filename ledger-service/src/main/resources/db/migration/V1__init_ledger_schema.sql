-- V1: Ledger Schema (Double-Entry Accounting)
-- Stored in minor units (BIGINT) to avoid floating point errors.
-- Optimized for UUIDv7 Partitioning.

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL, -- Logical link to User Service
    currency VARCHAR(3) NOT NULL,
    name VARCHAR(255),
    type VARCHAR(50) NOT NULL, -- LIABILITY, ASSET, EQUITY
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    reference VARCHAR(255) UNIQUE NOT NULL, -- Client-side idempotency key
    type VARCHAR(50) NOT NULL, -- P2P_TRANSFER, TOP_UP, WITHDRAWAL, FEE
    status VARCHAR(50) NOT NULL, -- PENDING, COMMITTED, FAILED
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    amount BIGINT NOT NULL, -- Positive for Credit, Negative for Debit (or separate type column)
    type VARCHAR(10) NOT NULL, -- DEBIT, CREDIT
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE balances (
    account_id UUID PRIMARY KEY REFERENCES accounts(id),
    currency VARCHAR(3) NOT NULL,
    available_amount BIGINT NOT NULL DEFAULT 0,
    pending_amount BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1 -- Optimistic Locking
);

-- Indices for performance
CREATE INDEX idx_entries_transaction_id ON entries(transaction_id);
CREATE INDEX idx_entries_account_id ON entries(account_id);
