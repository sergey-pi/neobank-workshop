# NeoBank — Neo-Bank Backend Workshop

A production-architecture neo-bank backend built with **Java 21**, **Spring Boot 4**, **jOOQ**, **PostgreSQL**, and **Redis**. Designed as a hands-on workshop to demonstrate real-world microservice patterns: double-entry accounting, the Outbox pattern, optimistic locking, KYC gating, rate limiting, Redis caching, and idempotency.

---

## Architecture

Three independent microservices, each with its own PostgreSQL database — no shared schema, no cross-DB foreign keys. Services are coupled only via `user_id` UUID. All services share a Redis instance for caching and atomic counters.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             NeoBank Platform                                │
│                                                                             │
│  ┌─────────────────────┐   ┌─────────────────────┐   ┌─────────────────────┐│
│  │   user-service      │   │   ledger-service     │   │  payment-service    ││
│  │   :8081             │   │   :8082              │   │   :8083             ││
│  │                     │   │                      │   │                     ││
│  │  Identity & Auth    │←──│  KYC gate (HTTP)     │   │  Payment Orders     ││
│  │  KYC / Profiles     │   │  Double-Entry        │   │  Outbox Pattern     ││
│  │  Rate limiting      │   │  Accounting          │   │  Idempotency (Redis)││
│  │  KYC cache (Redis)  │   │  Optimistic Locking  │   │  State Machine      ││
│  │                     │   │  Spend counter Redis │   │                     ││
│  │  neobank_user_db    │   │  neobank_ledger_db   │   │  neobank_payment_db ││
│  └─────────────────────┘   └─────────────────────┘   └─────────────────────┘│
│                 │                    │                         │             │
│                 └────────────────────┴─────────────────────────┘             │
│                                      │                                       │
│                               ┌─────────────┐                               │
│                               │    Redis 7  │                               │
│                               │    :6379    │                               │
│                               └─────────────┘                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

See [`architecture_c4.puml`](./architecture_c4.puml) for the full C4 container diagram.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.6 |
| Database access | jOOQ (no JPA/Hibernate) |
| Migrations | Flyway 12 |
| Relational DB | PostgreSQL 16 |
| Cache / Counters | Redis 7 (`StringRedisTemplate`) |
| Security | Spring Security + BCrypt, in-memory rate limiter |
| Code style | Checkstyle (Spring-style, 120-char lines) |
| CI | GitHub Actions |

---

## Prerequisites

- Java 21+
- Maven 3.9+ (or use the included `./mvnw`)
- Docker & Docker Compose

---

## Getting Started

### 1. Start PostgreSQL and Redis

```bash
# Local development — start services natively
brew services start postgresql@16
brew services start redis
```

> `docker-compose.yml` is for CI only. For local development start PostgreSQL and Redis as native services.

### 2. Apply Flyway migrations

```bash
./mvnw install -pl common -am -DskipTests   # build common module first
./mvnw flyway:migrate -pl user-service
./mvnw flyway:migrate -pl ledger-service
./mvnw flyway:migrate -pl payment-service
```

### 3. Build all services

```bash
./mvnw clean install
```

### 4. Run services

```bash
./mvnw spring-boot:run -pl user-service    # http://localhost:8081
./mvnw spring-boot:run -pl ledger-service  # http://localhost:8082
./mvnw spring-boot:run -pl payment-service # http://localhost:8083
```

### Environment overrides

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_USER` | `neouser` | PostgreSQL user |
| `DB_PASSWORD` | `neopassword` | PostgreSQL password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `ERROR_BASE_URL` | `https://neobank.com/errors` | RFC 7807 error type base URL |
| `RATE_LIMIT_RPM` | `10` | Max POST /register requests per minute per IP |
| `MAX_TRANSFER_AMOUNT` | `1000000` | Per-transfer cap in minor units ($10,000) |
| `DAILY_SPEND_LIMIT` | `5000000` | Daily debit cap per account in minor units ($50,000) |
| `USER_SERVICE_URL` | `http://localhost:8081` | ledger-service → user-service base URL |
| `OUTBOX_POLL_INTERVAL_MS` | `5000` | Outbox poller interval in milliseconds |
| `OUTBOX_MAX_RETRIES` | `3` | Max outbox event delivery attempts before FAILED |

---

## API Reference

### User Service — `http://localhost:8081`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/users/register` | Register a new user (rate-limited: 10 req/min per IP) |
| `GET` | `/api/v1/users` | List all users |
| `GET` | `/api/v1/users/{userId}/kyc-status` | Get KYC verification status (Redis-cached, 5 min TTL) |
| `GET` | `/health` | Health check |

**Register a user:**
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

**Check KYC status:**
```bash
curl http://localhost:8081/api/v1/users/{userId}/kyc-status
# → {"userId":"…","kycStatus":"PENDING"}
# KYC transitions: PENDING → APPROVED (manual or admin update for MVP)
```

### Ledger Service — `http://localhost:8082`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/accounts` | Create an account + balance row |
| `GET` | `/api/v1/accounts` | List all accounts |
| `POST` | `/api/v1/transactions/transfer` | Execute a double-entry transfer |
| `GET` | `/api/v1/transactions` | List all transactions |
| `GET` | `/health` | Health check |

**Create account:**
```bash
curl -X POST http://localhost:8082/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"userId":"<uuid>","currency":"USD","name":"Main Wallet","type":"LIABILITY"}'
```

**Execute a transfer:**
```bash
curl -X POST http://localhost:8082/api/v1/transactions/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": "<uuid>",
    "toAccountId":   "<uuid>",
    "amount":        5000,
    "currency":      "USD",
    "description":   "Rent payment"
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

Transfer guards (in order of evaluation):
1. `amount > MAX_TRANSFER_AMOUNT` → **422** (per-transaction limit)
2. `fromAccount.user.kycStatus != APPROVED` → **403** (KYC gate)
3. `todaySpend + amount > DAILY_SPEND_LIMIT` → **422** (daily limit)
4. `availableBalance < amount` → **422** (insufficient funds)

### Payment Service — `http://localhost:8083`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/payments` | Submit a payment order (idempotency via `idempotencyKey`) |
| `GET` | `/api/v1/payments` | List all payment orders |
| `GET` | `/health` | Health check |

**Submit a payment:**
```bash
curl -X POST http://localhost:8083/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "senderId":       "<uuid>",
    "receiverId":     "<uuid>",
    "amount":         1000,
    "currency":       "USD",
    "description":    "Invoice #42",
    "idempotencyKey": "unique-client-key-abc123"
  }'
```

Submitting the same `idempotencyKey` twice returns the cached response without creating a duplicate order (Redis key `idem:{key}`, TTL 24 h).

---

## Key Design Patterns

### Double-Entry Accounting
Every transfer creates exactly two `entries` rows within a single `transactions` record:
- **Debit** on `fromAccount` — stored as a negative `BIGINT` amount
- **Credit** on `toAccount` — stored as a positive `BIGINT` amount

The ledger can never be inconsistent because both entries are written in the same `@Transactional` boundary.

### Optimistic Locking
`balances.version` is incremented with every update. The debit UPDATE includes `WHERE available_amount >= requested_amount`, which means the UPDATE returns 0 rows if the balance was concurrently drained — triggering a 422 without needing a pessimistic lock.

### Transactional Outbox
`payment_orders` and `payment_outbox` are written in the same DB transaction. The `OutboxPoller` (scheduled every 5 s) processes `PENDING` events using `SELECT FOR UPDATE SKIP LOCKED` to be safe under concurrent instances. Failed deliveries are retried up to `OUTBOX_MAX_RETRIES` times then marked `FAILED`.

### KYC Gate (inter-service HTTP)
`ledger-service` calls `user-service GET /api/v1/users/{userId}/kyc-status` via `RestClient` before allowing any transfer. The gateway **fails open** on network errors to avoid a hard availability dependency in the MVP. `user-service` caches the result in Redis (`kyc:{userId}`, TTL 5 min) to reduce DB load on high-traffic paths.

### Redis Spend Counters
After every successful transfer, `ledger-service` atomically increments a Redis key `spend:{accountId}:{date}` via `INCRBY`. Future daily-limit checks read this counter (O(1)) instead of aggregating the `entries` table. The key expires after 25 hours so it covers the full UTC day plus one hour of clock skew. If Redis is unavailable, the service falls back to the SQL aggregate query.

### In-Memory Rate Limiting
`user-service` applies a sliding-window rate limiter (per client IP) to `POST /register` using a `ConcurrentHashMap<IP, long[]>` of request timestamps. Requests beyond the limit receive `429 Too Many Requests` with a `Retry-After: 60` header. Replace with Redis-backed Bucket4j for multi-node deployments.

### Payment Idempotency
`payment-service` stores the mapping `idempotencyKey → orderId` in Redis (`idem:{key}`, TTL 24 h) using `SET NX`. A duplicate submission within the TTL window returns the original response without a second DB write.

### RFC 7807 Problem Details
All error responses follow [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807). Every `ProblemDetail` includes:
- `status` — HTTP status code
- `detail` — human-readable message
- `code` — machine-readable error code (e.g. `FORBIDDEN`, `UNPROCESSABLE`)
- `type` — URI pointing to the error documentation

---

## Development

### Run tests

```bash
# Requires: docker compose up -d (PostgreSQL + Redis must be running)

./mvnw test -pl user-service
./mvnw test -pl ledger-service
./mvnw test -pl payment-service

# Single test class
./mvnw test -pl ledger-service -Dtest=LedgerServiceIntegrationTest
```

### Schema changes

1. Add `src/main/resources/db/migration/V{N}__{description}.sql`
2. Run `./mvnw flyway:migrate -pl <service>`
3. Run `./mvnw generate-sources -pl <service>`

> ⚠️ Never hand-edit files under `target/generated-sources/jooq/` — they are regenerated from the live DB schema.

### Code style

This project enforces a Spring-style Checkstyle configuration (4-space indent, 120-char lines). Run before committing:

```bash
./mvnw checkstyle:check
```

Configuration lives at `.github/checkstyle/neobank_checks.xml`.

### Double-Entry Accounting
Every transfer in the ledger creates exactly two `entries` rows inside a single `transactions` record:
- **Debit entry** — negative amount on the source account (outflow)
- **Credit entry** — positive amount on the destination account (inflow)

The amounts must balance to zero. There are no partial writes — both entries and both balance updates are committed in a single ACID transaction.

### Transfer Execution Order (14 steps, must not be reordered)

```
1.  Per-transaction limit check      (no I/O — fast guard)
2.  Resolve fromAccount → userId     (SELECT accounts)
3.  KYC gate                         (HTTP to user-service, outside tx, fails open)
4.  Daily spend check (Redis)        (fast-path; -1 = Redis down → SQL fallback at step 8)
5.  SELECT FOR UPDATE fromBalance    (opens transaction)
6.  Fetch toBalance                  (existence + currency)
7.  Currency validation
8.  SQL daily spend fallback         (only when Redis was unavailable)
9.  Sufficient funds check
10. INSERT transactions (PENDING)
11. INSERT entries ×2                (debit -amount, credit +amount)
12. UPDATE balances debit            (lock held from step 5)
13. UPDATE balances credit
14. Increment Redis spend counter    (after DB commit succeeds)
```

**Why this order matters:**
- Steps 1–4 are pure validation — no writes yet, so rejection costs nothing.
- The `SELECT FOR UPDATE` in step 5 establishes a pessimistic lock on the source row, preventing double-spend under concurrency.
- Steps 5–13 are all inside one `TransactionTemplate` boundary. If any step fails, the entire transaction rolls back — the `PENDING` record and both entries are never committed.
- Step 14 happens *after* the DB commit. If the Redis increment is skipped due to a crash, the next daily-limit check falls back to SQL automatically.

### Currency Validation
Transfers are rejected early (before any writes) if:
- `fromAccount.currency ≠ toAccount.currency` — cross-currency transfers require an FX rate, which is not implemented.
- `request.currency ≠ fromAccount.currency` — the caller must specify the correct currency explicitly; mismatches are a client error.

### Optimistic Locking
`balances.version` is incremented on every balance update. A return of 0 updated rows means either the row was modified concurrently or the account does not exist. The transaction rolls back in both cases.

### Transactional Outbox
`payment_outbox` is written in the same DB transaction as `payment_orders`, guaranteeing at-least-once event delivery even if the application crashes between write and publish.

### No JPA
All DB access uses jOOQ's type-safe DSL. Generated table/column constants (e.g. `Balances.BALANCES.AVAILABLE_AMOUNT`) enforce compile-time schema safety. There is no Hibernate session cache or lazy loading.

### Minor Units
All monetary amounts are stored as `BIGINT` (cents/pence). `1000` means `10.00 USD`. Never use `Double` or `BigDecimal` in the DB layer.

---

## Project Structure

```
workshop/
├── common/                    # Shared exceptions, GlobalExceptionHandler (RFC 7807)
├── user-service/              # Identity, registration, KYC, rate limiting
│   └── src/main/java/…/
│       ├── cache/             # KycStatusCache (Redis, TTL 5 min)
│       ├── config/            # SecurityConfig, FilterRegistrationBean
│       ├── controller/        # UserController
│       ├── dto/               # UserRegistrationRequest, UserResponse, KycStatusResponse
│       ├── filter/            # RateLimiterFilter (sliding window, per-IP)
│       └── service/           # UserService
├── ledger-service/            # Double-entry accounting, transfers, spend limits
│   └── src/main/java/…/
│       ├── cache/             # SpendCounterCache (Redis INCRBY, TTL 25 h)
│       ├── controller/        # AccountController, TransactionController
│       ├── dto/               # CreateAccountRequest, TransferRequest, TransferResponse
│       ├── gateway/           # KycGateway (RestClient → user-service)
│       └── service/           # LedgerService (ACID transfer, 9-step order)
└── payment-service/           # Payment orders, Outbox pattern, idempotency
    └── src/main/java/…/
        ├── cache/             # IdempotencyCache (Redis SET NX, TTL 24 h)
        ├── controller/        # PaymentController
        ├── dto/               # PaymentRequest, PaymentResponse
        └── service/           # PaymentService, OutboxPoller
```

---

## License

[MIT](./LICENSE) © 2026 Sergey
