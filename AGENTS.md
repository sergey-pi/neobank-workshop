# NeoBank Workshop — Agent Quick-Start

Multi-module Maven project (Java 21, Spring Boot 4.0.6). Three independent microservices, each with its own PostgreSQL database. All services share a Redis instance for caching, atomic counters, and idempotency.

| Service | Port | DB | Responsibility |
|---|---|---|---|
| `user-service` | 8081 | `neobank_user_db` | Identity, registration, KYC, rate limiting |
| `ledger-service` | 8082 | `neobank_ledger_db` | Double-entry accounting, balances, spend limits |
| `payment-service` | 8083 | `neobank_payment_db` | Payment orders, Outbox pattern, idempotency |

Shared cross-cutting code lives in the `common` Maven module.
Full details: [`.github/copilot-instructions.md`](.github/copilot-instructions.md)

---

## Essential Commands

```bash
# 1. Start PostgreSQL AND Redis (required for everything below)
docker compose up -d

# 2. Install common before anything else (parent POM + common jar must be in local repo)
./mvnw install -pl common -am -DskipTests

# 3. Apply schema migrations
./mvnw flyway:migrate -pl user-service
./mvnw flyway:migrate -pl ledger-service
./mvnw flyway:migrate -pl payment-service

# 4. Regenerate jOOQ sources (run after any schema change)
./mvnw generate-sources -pl user-service    # or ledger-service / payment-service

# 5. Build everything
./mvnw clean install -DskipTests

# 6. Run tests for one service
./mvnw test -pl ledger-service
```

---

## Non-Negotiable Rules

1. **No JPA/Hibernate.** All DB access uses jOOQ `DSLContext` directly.
2. **Money is BIGINT minor units** (cents). Never `Double` or `BigDecimal` in the DB layer.
3. **Never hand-edit generated jOOQ files** in `target/generated-sources/jooq/`.
4. **Schema changes = new Flyway file** `V{N}__{description}.sql` → migrate → generate-sources.
5. **`common` first.** Any build that references `common` requires `./mvnw install -pl common -am` to run first.
6. **Git workflow**: `WSNB - <message>` commit prefix, no Co-authored-by, branch per feature, PR per change.
7. **Tests are mandatory.** Every code change must include or update integration tests. Run `./mvnw test -pl <service>` before pushing — a PR with failing or missing tests will not be merged.
8. **Document all logic.** Public classes and non-trivial methods must have Javadoc. Include rationale, not just what the code does.
9. **Enums over magic strings.** Status values (`PENDING`, `APPROVED`, `REJECTED`, etc.) must be Java enums even when stored as strings in the DB. Convert at the boundary: `KycStatus.valueOf(dbString)` on read, `.name()` on write.
10. **Config defaults belong in `application.yml`, not in `@Value` annotations.** Use `@Value("${my.prop}")` — never `@Value("${my.prop:hardcoded-default}")`. The yml is the single source of truth for defaults; code should not carry fallback values.
11. **No magic numbers or strings.** Extract all literals used more than once (timeouts, limits, retry intervals, status codes) as named `private static final` constants at the top of the class.
12. **Java text blocks for multi-line strings.** Use `"""..."""` text blocks for inline JSON, SQL snippets, or any string spanning multiple lines. Never concatenate strings with `+` across lines.

---

## Infrastructure Overview

### PostgreSQL (port 5432)
Three isolated databases — one per service. No cross-DB queries or foreign keys. Schema managed by Flyway; code access via jOOQ generated types.

### Redis (port 6379)
Shared across all services. Used for:

| Service | Key pattern | TTL | Purpose |
|---|---|---|---|
| user-service | `kyc:{userId}` | 5 min | Cache KYC status responses from `user_profiles` |
| ledger-service | `spend:{accountId}:{date}` | 25 h | Atomic daily spend counter (INCRBY) |
| payment-service | `idem:{idempotencyKey}` | 24 h | Idempotency — prevent duplicate payment orders |

All Redis operations wrap in try-catch and **fail open** — Redis unavailability must never break a service. Fallback paths exist for every Redis call.

---

## Security Controls

### Rate limiting (user-service)
- `RateLimiterFilter` intercepts `POST /api/v1/users/register`
- Sliding window, keyed by client IP, in-memory `ConcurrentHashMap`
- Returns `429 Too Many Requests` + `Retry-After: 60` header
- Configurable via `security.rate-limit.requests-per-minute` (default 10)

### KYC gate (ledger-service)
- `KycGateway` calls `GET /api/v1/users/{userId}/kyc-status` via `RestClient` before every transfer
- Throws `ForbiddenException` (403) if `kycStatus != APPROVED`
- Fails open on network errors (logs warning, allows transfer)
- Result is cached in user-service Redis for 5 min — no repeated DB hits

### Transaction limits (ledger-service)
- Per-transfer max: `security.limits.max-transfer-amount` (default 1,000,000 = $10,000)
- Daily debit cap: `security.limits.daily-spend-limit` (default 5,000,000 = $50,000)
- Daily check uses Redis counter as fast-path; falls back to SQL aggregate if Redis is down
- Compound index `idx_entries_account_daily(account_id, type, created_at)` backs the SQL fallback

---

## Transfer Execution Order (LedgerService)

The 14-step order in `LedgerService.transfer()` is intentional and must not be reordered:

```
1.  Per-transaction limit check      ← cheapest, no I/O
2.  Resolve fromAccount → userId     ← single SELECT
3.  KYC gate (HTTP)                  ← outside transaction, fails open
4.  Daily spend check (Redis)        ← fast-path; -1 means Redis down → SQL fallback in step 8
5.  SELECT FOR UPDATE fromBalance    ← opens transaction, acquires lock
6.  Fetch toBalance                  ← existence + currency check
7.  Currency validation              ← both accounts + request must match
8.  SQL daily spend fallback         ← only executes if Redis was down (step 4 returned -1)
9.  Sufficient funds check
10. INSERT transactions (PENDING)
11. INSERT entries × 2 (DEBIT + CREDIT)
12. UPDATE balances debit            ← lock held from step 5
13. UPDATE balances credit
14. Increment Redis spend counter    ← after DB commit succeeds
```
