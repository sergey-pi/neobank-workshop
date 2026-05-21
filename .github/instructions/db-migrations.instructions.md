---
applyTo: "**/db/migration/**/*.sql"
---

# Database Migration Instructions

## Flyway Naming Convention

Files MUST follow `V{N}__{description}.sql` (two underscores):

```
V1__init_schema.sql         ✅
V2__add_kyc_flag.sql        ✅
V2_add_kyc_flag.sql         ❌ (single underscore — Flyway ignores it)
V02__add_kyc_flag.sql       ❌ (leading zero — breaks ordering)
```

The `{N}` must be strictly increasing within each service. Check existing files before choosing the next number.

## Schema Conventions

- **Primary keys**: `UUID` — never `SERIAL`/`BIGSERIAL`
- **Money**: `BIGINT` in minor units (cents/pence) — never `DECIMAL`, `NUMERIC`, or `FLOAT`
- **Timestamps**: `TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP`
- **Status columns**: `VARCHAR(50)` — not enums (enums are painful to alter)
- **JSONB**: use for extensible fields (`flags`, `metadata`, `destination_details`, `custom_settings`)
- **Optimistic locking**: `version BIGINT NOT NULL DEFAULT 1` on rows that need it (`balances`)

## Double-Entry Accounting (ledger-service only)

- `entries.amount`: **negative** = debit (outflow), **positive** = credit (inflow)
- Every transfer creates exactly **two** `entries` rows in the same `transactions` record
- `balances` is a denormalised summary updated atomically with each transfer

## After Every Migration

```bash
./mvnw flyway:migrate -pl <service>
./mvnw generate-sources -pl <service>
```

Never manually edit files under `target/generated-sources/jooq/`.
