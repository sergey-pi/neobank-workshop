# NeoBank Workshop — Agent Quick-Start

Multi-module Maven project (Java 21, Spring Boot 4.0.6). Three independent microservices, each with its own PostgreSQL database. All services share a Redis instance for caching, atomic counters, and idempotency.

| Service | Port | DB | Responsibility |
|---|---|---|---|
| `user-service` | 8081 | `neobank_user_db` | Identity, registration, login (JWT issuance), KYC, rate limiting |
| `ledger-service` | 8082 | `neobank_ledger_db` | Double-entry accounting, balances, spend limits, transfer idempotency (`Idempotency-Key`) |
| `payment-service` | 8083 | `neobank_payment_db` | Payment orders, Outbox pattern, idempotency |

Shared cross-cutting code lives in the `common` Maven module.
Two directories are **outside** the Maven aggregator: `frontend/` (React client, see [Frontend](#frontend-frontend)) and `core-engine/` (see [core-engine](#core-engine-standalone-module)).
Full details: [`.github/copilot-instructions.md`](.github/copilot-instructions.md)

---

## Essential Commands

```bash
# 0. One-time setup after cloning — activate pre-commit quality gate
git config core.hooksPath .githooks
# To bypass in emergencies: git commit --no-verify

# 1. Start PostgreSQL and Redis (native local services)
brew services start postgresql@16
brew services start redis
# Fallback: docker compose up -d  (postgres:16-alpine + redis:7-alpine, same images as CI)

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
| ledger-service | `transfer-idem:{idempotencyKey}` | 24 h | Transfer idempotency fast-path (`SET NX`, value = transactionId) |
| payment-service | `idem:{idempotencyKey}` | 24 h | Idempotency — prevent duplicate payment orders |

All Redis operations wrap in try-catch and **fail open** — Redis unavailability must never break a service. Fallback paths exist for every Redis call.

### Outbox poller (payment-service)
`OutboxPoller` runs on `@Scheduled(fixedDelayString = "${outbox.poll.interval-ms}")` (default 5000 ms, env `OUTBOX_POLL_INTERVAL_MS`). Each run processes up to 10 `PENDING` events, one transaction per event, using `SELECT … FOR UPDATE SKIP LOCKED` held through dispatch and status update. Failures back off exponentially (`outbox.poll.retry-base-delay-seconds`, default 30 s → 60 s → 120 s) and move to `FAILED` after `outbox.poll.max-retries` (default 3, env `OUTBOX_MAX_RETRIES`).

---

## Security Controls

### Rate limiting (user-service)
- `RateLimiterFilter` intercepts `POST /api/v1/users/register`
- Sliding window, keyed by client IP, Caffeine cache (bounded 100k entries, 2-min expiry)
- Returns `429 Too Many Requests` + `Retry-After: 60` header
- Configurable via `security.rate-limit.requests-per-minute` (default 10, env `RATE_LIMIT_RPM`)
- `X-Forwarded-For` is trusted only when the TCP peer is in `security.rate-limit.trusted-proxy-cidrs` (env `TRUSTED_PROXY_CIDRS`, default empty = never trusted)

### Internal service key (user-service)
- `InternalServiceKeyFilter` requires header `X-Service-Key` on `GET /api/v1/users/{userId}/kyc-status`
- Key: `security.internal-service-key` (env `INTERNAL_SERVICE_KEY`). If empty, the endpoint is unprotected and a startup warning is logged
- ledger-service sends the same key via `services.internal-service-key`

### KYC gate (ledger-service)
- `KycGateway` calls `GET /api/v1/users/{userId}/kyc-status` via `RestClient` before every transfer
- Throws `ForbiddenException` (403) if `kycStatus != APPROVED`
- Connect timeout 2 s, read timeout 5 s
- **Fails closed** on network errors: throws `ServiceUnavailableException` (503) — transfers are rejected when KYC status cannot be confirmed
- Result is cached in user-service Redis for 5 min — no repeated DB hits

### Transaction limits (ledger-service)
- Per-transfer max: `security.limits.max-transfer-amount` (default 1,000,000 = $10,000)
- Daily debit cap: `security.limits.daily-spend-limit` (default 5,000,000 = $50,000)
- Daily check uses the Redis counter as an early fast-fail only; the authoritative SQL aggregate **always** runs inside the transaction (step 8)
- Compound index `idx_entries_account_daily(account_id, type, created_at)` backs the SQL check

### Transfer idempotency (ledger-service)
- `POST /api/v1/transactions/transfer` accepts an optional `Idempotency-Key` header
- Redis fast-path: `TransferIdempotencyCache` key `transfer-idem:{key}` (TTL 24 h) — a hit returns the original `transactionId` with no DB work
- Authoritative guard: `transactions.idempotency_key` with `UNIQUE` constraint `uq_transactions_idempotency_key` (migration `V4__add_transfer_idempotency_key.sql`); the insert uses `ON CONFLICT (idempotency_key) DO NOTHING` and returns the existing transaction on conflict
- The key is stored in Redis only after the DB commit succeeds; requests without the header are not deduplicated

### JWT authentication
- **Login:** `POST /api/v1/users/login` with `{"email","password"}` → `LoginResponse {accessToken, userId, email, expiresIn}` (seconds). Wrong credentials → 401, suspended user → 403
- **Issuing:** `JwtUtil` (`user-service/.../service/JwtUtil.java`) signs HS256 tokens with `sub` = userId and an `email` claim. Config: `jwt.secret` (env `JWT_SECRET`, ≥ 256-bit) and `jwt.expiration-hours` (env `JWT_EXPIRATION_HOURS`, default 1)
- **Library:** jjwt 0.13.x (`jjwt-api` + runtime `jjwt-impl`, `jjwt-jackson`) in `user-service` and `common`
- **Authenticated requests:** send `Authorization: Bearer <accessToken>`. In ledger-service and payment-service, `common`'s `BearerTokenFilter` (registered on `/api/*` in each service's `WebConfig`) validates the token with the shared `jwt.secret` and sets request attributes `userId` / `userEmail`. An invalid or expired token → 401; a missing header is currently allowed through (auth is not yet enforced there)
- **user-service:** `SecurityConfig` permits `/health`, `/api/v1/users/register`, `/api/v1/users/login`, `/api/v1/users/*/kyc-status` and the Swagger/OpenAPI paths; every other request requires authentication. No bearer-token filter is wired into the user-service security chain yet
- `JWT_SECRET` must be identical across all three services

---

## Transfer Execution Order (LedgerService)

The 14-step order in `LedgerService.transfer()` (preceded by the step-0 idempotency check) is intentional and must not be reordered:

```
0.  Idempotency fast-path (Redis)    ← only if Idempotency-Key header present; hit → return cached transactionId
1.  Per-transaction limit check      ← cheapest, no I/O
2.  Resolve fromAccount → userId     ← single SELECT
3.  KYC gate (HTTP)                  ← outside transaction, fails closed (503)
4.  Daily spend check (Redis)        ← early fast-fail only; -1 means Redis down → skipped
5.  SELECT FOR UPDATE fromBalance    ← opens transaction, acquires lock
6.  Fetch toBalance                  ← existence + currency check
7.  Currency validation              ← both accounts + request must match
8.  SQL daily spend check            ← authoritative, always runs, serialised by step-5 lock
9.  Sufficient funds check
10. INSERT transactions (PENDING)    ← ON CONFLICT (idempotency_key) DO NOTHING → return existing tx
11. INSERT entries × 2 (DEBIT + CREDIT)
12. UPDATE balances debit            ← lock held from step 5
13. UPDATE balances credit           ← then mark transaction COMPLETED
14. Increment Redis spend counter    ← after DB commit; then store transfer-idem:{key} in Redis
```

---

## Frontend (`frontend/`)

React 18 + Vite single-page client (plain JavaScript/JSX, React Router 6). Not part of the Maven build and not built in CI.

```bash
cd frontend
npm ci
npm run dev      # Vite dev server on http://localhost:3000, proxies /api/v1/* to 8081/8082/8083
npm run build    # production build — use as the verification step (there is no test or lint script)
npm run preview  # serve the production build
```

- All API calls go through `src/api/*Api.js` using `fetchWithTimeout` (10 s default) from `src/api/apiUtils.js`
- The whole app is wrapped in a `react-error-boundary` `ErrorBoundary` (`src/App.jsx`)
- JWT is kept in `sessionStorage` (`accessToken`, `userId`, `email`) and sent as `Authorization: Bearer …`; a 401 clears the session and redirects to `/login`
- Backend CORS origins: `cors.allowed-origins` / env `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000`)

Detailed conventions: [`.github/instructions/frontend.instructions.md`](.github/instructions/frontend.instructions.md)

---

## core-engine (standalone module)

`core-engine/` is a standalone Spring Boot scaffold (`com.neobank:core-engine`, its own `spring-boot-starter-parent` 4.0.3). It is **not** a module of the root `pom.xml` aggregator, so `./mvnw clean install` at the repo root does not build or test it, and the pre-commit hook and CI do not check it. Build it with its own wrapper:

```bash
cd core-engine
./mvnw clean verify
```
