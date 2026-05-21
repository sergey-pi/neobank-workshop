# NeoBank — Neo-Bank Backend Workshop

A production-architecture neo-bank backend built with **Java 21**, **Spring Boot 4**, **jOOQ**, and **PostgreSQL**. Designed as a hands-on workshop to demonstrate real-world microservice patterns: double-entry accounting, the Outbox pattern, optimistic locking, CQRS, and PII isolation.

---

## Architecture

Three independent microservices, each with its own database — no shared schema, no cross-DB foreign keys. Services are coupled only via `user_id` UUID.

```
┌─────────────────────┐   ┌─────────────────────┐   ┌─────────────────────┐
│   user-service      │   │   ledger-service     │   │  payment-service    │
│   :8081             │   │   :8082              │   │   :8083             │
│                     │   │                      │   │                     │
│  Identity & Auth    │   │  Double-Entry        │   │  Payment Orders     │
│  KYC / Profiles     │   │  Accounting          │   │  Outbox Pattern     │
│  BCrypt passwords   │   │  Optimistic Locking  │   │  State Machine      │
│                     │   │                      │   │                     │
│  neobank_user_db    │   │  neobank_ledger_db   │   │  neobank_payment_db │
└─────────────────────┘   └─────────────────────┘   └─────────────────────┘
```

See [`architecture_c4.puml`](./architecture_c4.puml) for the full C4 container diagram.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.3 |
| Database access | jOOQ 3.19 (no JPA/Hibernate) |
| Migrations | Flyway 10 |
| Database | PostgreSQL 16 |
| Security | Spring Security + BCrypt |

---

## Prerequisites

- Java 21+
- Maven 3.9+ (or use the included `./mvnw`)
- Docker & Docker Compose

---

## Getting Started

### 1. Start PostgreSQL

```bash
docker compose up -d
```

This creates three databases: `neobank_user_db`, `neobank_ledger_db`, `neobank_payment_db`.

### 2. Configure credentials (optional)

The defaults work out of the box. To override, copy the example env file:

```bash
cp .env.example .env
# edit .env as needed
```

### 3. Apply Flyway migrations

```bash
./mvnw flyway:migrate -pl user-service
./mvnw flyway:migrate -pl ledger-service
./mvnw flyway:migrate -pl payment-service
```

### 4. Build all services

```bash
./mvnw clean install
```

### 5. Run a service

```bash
./mvnw spring-boot:run -pl user-service
./mvnw spring-boot:run -pl ledger-service
./mvnw spring-boot:run -pl payment-service
```

---

## API Overview

### User Service — `http://localhost:8081`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/users/register` | Register a new user |
| `GET` | `/api/v1/users` | List all users |
| `GET` | `/health` | Health check |

**Register example:**
```bash
curl -X POST http://localhost:8081/api/v1/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice@example.com",
    "password": "password123",
    "firstName": "Alice",
    "lastName": "Smith",
    "phoneNumber": "+1234567890",
    "dateOfBirth": "1990-01-15",
    "countryCode": "US",
    "addressLine1": "123 Main St",
    "city": "New York",
    "postalCode": "10001"
  }'
```

### Ledger Service — `http://localhost:8082`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/accounts` | Create an account + balance |
| `GET` | `/api/v1/accounts` | List all accounts |
| `POST` | `/api/v1/transactions/transfer` | Execute a double-entry transfer |
| `GET` | `/api/v1/transactions` | List all transactions |
| `GET` | `/health` | Health check |

**Create account example:**
```bash
curl -X POST http://localhost:8082/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "<user-id-from-registration>",
    "currency": "USD",
    "name": "Main Wallet",
    "type": "LIABILITY"
  }'
```

**Transfer example:**
```bash
curl -X POST http://localhost:8082/api/v1/transactions/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": "<sender-account-id>",
    "toAccountId": "<receiver-account-id>",
    "amount": 1000,
    "currency": "USD",
    "description": "Send money"
  }'
```

> `amount` is in minor units — `1000` = 10.00 USD. Both accounts must share the same currency.

### Payment Service — `http://localhost:8083`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/payments` | Submit a payment order |
| `GET` | `/api/v1/payments` | List all payment orders |
| `GET` | `/health` | Health check |

---

## Development

### Run tests

```bash
# All tests in a module
./mvnw test -pl ledger-service

# Single test class
./mvnw test -pl user-service -Dtest=UserServiceTest
```

### Regenerate jOOQ sources

After any schema change, apply the migration then regenerate:

```bash
./mvnw flyway:migrate -pl <service>
./mvnw generate-sources -pl <service>
```

> ⚠️ Never hand-edit files under `target/generated-sources/jooq/` — they are regenerated from the live DB schema.

### Adding a schema change

1. Create `src/main/resources/db/migration/V{N}__{description}.sql`
2. Run `./mvnw flyway:migrate -pl <service>`
3. Run `./mvnw generate-sources -pl <service>`

---

## Key Design Patterns

### Double-Entry Accounting
Every transfer in the ledger creates exactly two `entries` rows inside a single `transactions` record:
- **Debit entry** — negative amount on the source account (outflow)
- **Credit entry** — positive amount on the destination account (inflow)

The amounts must balance to zero. There are no partial writes — both entries and both balance updates are committed in a single ACID transaction.

### Transfer Execution Order (9 steps, must not be reordered)

```
1. Per-transaction limit check (no I/O — fast guard)
2. Resolve fromAccount → userId  (SELECT accounts)
3. KYC gate (HTTP to user-service, fails open on network error)
4. Daily spend check (Redis fast-path; SQL aggregate fallback if Redis miss)
5. INSERT transactions (status = PENDING)
6. INSERT entries ×2  (debit -amount, credit +amount)
7. UPDATE balances debit  (SELECT FOR UPDATE held; rolls back if 0 rows)
8. UPDATE balances credit (guarded — 0 rows = destination missing, rolls back)
9. Increment Redis spend counter (only after DB commit succeeds)
```

**Why this order matters:**
- Step 1–4 are pure validation — no writes yet, so rejection costs nothing.
- The `SELECT FOR UPDATE` in step 7 establishes a pessimistic lock on the source row, preventing double-spend under concurrency.
- Steps 5–8 are all inside one `@Transactional` boundary. If step 7 or 8 fails, the entire transaction rolls back — the `PENDING` record and both entries are never committed.
- Step 9 happens *after* the DB commit. If the Redis increment is skipped due to a crash, the next daily-limit check falls back to SQL automatically.

### Currency Validation
Transfers are rejected early (before any writes) if:
- `fromAccount.currency ≠ toAccount.currency` — cross-currency transfers require an FX rate, which is not implemented.
- `request.currency ≠ fromAccount.currency` — the caller must specify the correct currency explicitly; mismatches are a client error.

Both checks happen in step 1 using `balances.currency`, which is fetched as part of the `SELECT FOR UPDATE`.

### Optimistic Locking
`balances.version` is incremented on every balance update. A return of 0 updated rows means either the row was modified concurrently or the account does not exist. The transaction rolls back in both cases.

### Transactional Outbox
`payment_outbox` is written in the same DB transaction as `payment_orders`, guaranteeing at-least-once event delivery even if the application crashes between write and publish.

### No JPA
All DB access uses jOOQ's type-safe DSL. Generated table/column constants (e.g. `Balances.BALANCES.AVAILABLE_AMOUNT`) enforce compile-time schema safety. There is no Hibernate session cache or lazy loading.

### Minor Units
All monetary amounts are stored as `BIGINT` (cents/pence). `1000` means `10.00 USD`. Never use `Double` or `BigDecimal` in the DB layer.

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-change`
3. Commit your changes: `git commit -m "feat: describe your change"`
4. Push and open a Pull Request

Please keep PRs focused — one concern per PR. For larger changes, open an issue first to discuss the approach.

---

## License

[MIT](./LICENSE) © 2026 Sergey
