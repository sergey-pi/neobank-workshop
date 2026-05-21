-- V1: Payment State Machine & Outbox Schema

CREATE TABLE payment_orders (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    type VARCHAR(50) NOT NULL, -- INTERNAL_P2P, SEPA, SWIFT
    status VARCHAR(50) NOT NULL, -- INITIATED, PENDING_LEDGER, SETTLED, FAILED
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    destination_details JSONB NOT NULL,
    external_reference VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment_outbox (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL, -- PaymentOrder
    aggregate_id UUID NOT NULL,
    type VARCHAR(255) NOT NULL, -- PAYMENT_SETTLED, PAYMENT_FAILED
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_outbox_status ON payment_outbox(status);
