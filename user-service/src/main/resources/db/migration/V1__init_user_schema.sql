-- V1: Initial Auth & User Schema
-- Optimized for PII Isolation and UUIDv7 (Time-Sortable)

CREATE TABLE users (
    id UUID PRIMARY KEY, -- Application should generate UUIDv7
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    role VARCHAR(50) NOT NULL DEFAULT 'CUSTOMER',
    plan_tier VARCHAR(50) NOT NULL DEFAULT 'STANDARD',
    flags JSONB, -- Schemaless Feature Flags
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_profiles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    legal_entity VARCHAR(50) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20) UNIQUE,
    kyc_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    date_of_birth DATE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_addresses (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES user_profiles(id),
    country_code VARCHAR(2) NOT NULL,
    address_line_1 VARCHAR(255) NOT NULL,
    city VARCHAR(100),
    postal_code VARCHAR(50),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to TIMESTAMP WITH TIME ZONE
);

CREATE TABLE user_settings (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    language VARCHAR(5) NOT NULL DEFAULT 'en-US',
    default_currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    theme VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    push_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    marketing_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    biometric_login_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    custom_settings JSONB,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    device_name VARCHAR(255),
    device_id VARCHAR(255) UNIQUE NOT NULL,
    push_token VARCHAR(255),
    last_login_ip VARCHAR(45),
    last_login_at TIMESTAMP WITH TIME ZONE,
    is_trusted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- V2: Transaction Limits (Isolated from core User table for performance)
CREATE TABLE user_transaction_limits (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    daily_limit BIGINT NOT NULL, -- minor units (cents)
    monthly_limit BIGINT NOT NULL,
    per_transaction_limit BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
