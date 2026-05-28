# NeoBank Production Readiness — Gap Analysis
_Generated 2026-05-22 via 4-agent parallel review_

---

## Summary

| Category | P0 (Ship-stopper) | P1 (Launch requirement) | P2 (Post-launch critical) | P3 (Nice-to-have) |
|---|---|---|---|---|
| Security | 6 | 4 | 4 | 1 |
| Scaling / Threading | 3 | 3 | 3 | 1 |
| Plan Gaps | 5 | 5 | 5 | 2 |
| UX / API Design | 4 | 5 | 7 | 4 |
| **Total** | **18** | **17** | **19** | **8** |

---

## 🔴 P0 — Production Blockers (ship-stoppers)

### Security P0s

| # | Area | Finding | Recommendation |
|---|---|---|---|
| S-1 | **No TLS** | Zero HTTPS across all 3 services. Credentials, PII, amounts in plaintext. Violates PCI-DSS 4.2.1, GDPR Art. 32. | Enforce via reverse proxy (nginx/ALB), TLS 1.2+, HSTS header, HTTP→HTTPS redirect. |
| S-2 | **No auth on ledger/payment** | `ledger-service` and `payment-service` have zero authentication. CORS is browser-only. Any curl call bypasses it. (issue #55) | JWT Bearer validation filter in both services. Share signing key with user-service. |
| S-3 | **IDOR — mass data exposure** | `GET /accounts` returns ALL users' accounts; `GET /transactions` returns all transactions. (issue #51) | Propagate `userId` from JWT claims into all queries. Add `WHERE user_id = :me`. |
| S-4 | **No MFA/SCA** | No 2FA. `biometric_login_enabled` stored but never enforced. PSD2 SCA required for EU payments >€30. | TOTP (RFC 6238) or SMS OTP. Account lockout after N failed logins. |
| S-5 | **Service key empty default disables its own filter** | `INTERNAL_SERVICE_KEY` defaults to `""`. `InternalServiceKeyFilter` checks `if (key.isEmpty()) return` — disabling the filter entirely in any env where the var is unset. | Remove default. `application.yml` must fail-fast if key is blank. CI pipeline must reject empty secret. |
| S-6 | **jOOQ DEBUG logs passwords** | All `logback-spring.xml` files log `org.jooq` at DEBUG with no `<springProfile name="!prod">` guard. jOOQ DEBUG prints full SQL with bound values — including `INSERT INTO users … 'plaintextPassword'`. | Wrap jOOQ logger in `<springProfile name="!prod">` or set to `WARN` unconditionally. |

### Scaling/Threading P0s

| # | Area | Finding | Recommendation |
|---|---|---|---|
| T-1 | **OutboxPoller: FOR UPDATE lock released before dispatch** | `fetchPendingBatch()` runs outside a transaction. `SELECT FOR UPDATE SKIP LOCKED` row locks are released immediately at auto-commit. Two instances can fetch the same rows. | Move SELECT + dispatch + status-UPDATE into one `TransactionTemplate` so the lock is held throughout. |
| T-2 | **Outbox dispatch is a no-op** | `executeDispatch()` only calls `log.info(...)`. Payments are logged and marked PROCESSED but no downstream system is notified. All payment events are silently swallowed. | Wire a `PaymentEventPublisher` interface (Kafka/HTTP) before go-live. The TODO comment is a production blocker. |
| T-3 | **Idempotency TOCTOU race** | `processPayment()` does cache-check → if-miss → insert — two concurrent requests with the same key both see cache miss, both insert, DB unique constraint fires as HTTP 500 instead of idempotent 200. | Change to `INSERT … ON CONFLICT (idempotency_key) DO NOTHING` returning the existing row. DB constraint is the source of truth; Redis is only a performance cache. |

### Plan Gap P0s

| # | Area | Finding | Priority | Effort |
|---|---|---|---|---|
| G-1 | **Graceful shutdown** | No `server.shutdown: graceful` in any service. In-flight DB transactions killed hard on pod termination → partial writes, data corruption. | P0 | S |
| G-2 | **Soft deletes** | All deletes are hard deletes. Financial records must be retained (PSD2, SOX). No `deleted_at` column anywhere. | P0 | M |
| G-3 | **Transfer idempotency** | `POST /api/v1/transactions/transfer` has no `Idempotency-Key` header support. Network retry = double debit. Payment service has it; ledger doesn't. | P0 | M |
| G-4 | **Dead letter / FAILED outbox** | After max retries, outbox event sits as `FAILED` forever. No alert, no DLQ, no requeue UI. Payments silently lost. | P0 | M |
| G-5 | **Secrets with plaintext defaults** | `DB_PASSWORD` defaults to `neopassword`, service key defaults to empty. Phase 15 (Vault) not started. | P0 | M |

### UX/API P0s

| # | Area | Finding | Recommendation |
|---|---|---|---|
| U-1 | **No login page / session** | No `LoginPage`. After register, no auth token stored. Frontend has no concept of "current user". All pages accessible to anyone. | Add `LoginPage` + `POST /api/v1/users/login` returning JWT. Store in `sessionStorage`. Guard routes with `<RequireAuth>`. |
| U-2 | **No current-user scope** | `DashboardPage` shows ALL accounts in DB (all users). There is no "my accounts" concept. | Derive `userId` from JWT on backend. Frontend sends token; backend scopes response to caller. |
| U-3 | **UUID-only transfer inputs** | Transfer page has raw UUID text boxes for from/to account. Users must manually copy UUIDs. Entirely unusable as a product. | Replace with `<select>` dropdowns populated from the current user's accounts. Show name + balance. |
| U-4 | **`GET /api/v1/users` leaks hashed passwords + full PII** | `AccountController` uses `fetchMaps()` on users table — returns `hashed_password`, DOB, address, phone to any caller. | Delete or gate behind admin auth. Return only a `UserResponse` DTO with non-sensitive fields. |

---

## 🟠 P1 — Launch Requirements

### Security P1s

| # | Area | Finding | Recommendation |
|---|---|---|---|
| S-7 | **PII unencrypted at rest** | `phone_number`, `date_of_birth`, `address_line_1` stored as plaintext. GDPR Art. 25, PCI-DSS 3.5. | `pgcrypto` AES-256 column encryption or application-layer encryption before writes. |
| S-8 | **No immutable audit trail** | No `audit_events` table. `entries` has no DB trigger blocking UPDATE/DELETE. Ledger entries can be silently modified. SOX, PSD2, FCA require tamper-evident records. | Append-only `audit_events` table. PostgreSQL trigger raising exception on UPDATE/DELETE of `entries`. Write-once S3 archival. |
| S-9 | **Redis unauthenticated** | No `requirepass` on Redis. No TLS for Redis connections. KYC status, spend counters, idempotency keys readable/writable by anyone with network access. | `spring.data.redis.password: ${REDIS_PASSWORD}`. Enable TLS. Restrict Redis CIDR via firewall. |
| S-10 | **Log retention: 7 days** | `maxHistory=7` in all logback configs. PCI-DSS 10.7 requires 12 months (3 immediately available). | Set `maxHistory=365+`. Ship to SIEM with long-term retention. Audit logs offloaded to write-once storage. |

### Scaling/Threading P1s

| # | Area | Finding | Recommendation |
|---|---|---|---|
| T-4 | **KycGateway: no circuit breaker** | With 7s total timeout, degraded user-service holds all Tomcat threads. 29 concurrent slow-KYC calls exhaust the thread pool and take down ledger-service. Cascade failure. | Add Resilience4j: `@CircuitBreaker` + `@Bulkhead(maxConcurrentCalls=20)` on `requireKycApproved`. Config: `slidingWindowSize=10`, `failureRateThreshold=50`, `waitDurationInOpenState=30s`. |
| T-5 | **In-memory rate limiter** | Caffeine rate limiter is per-JVM. Behind a load balancer, each instance allows `N × limit` requests. Completely ineffective at scale. | Migrate to Redis sliding window: Lua script `ZADD` + `ZREMRANGEBYSCORE` + `ZCARD` for atomic evaluation. |
| T-6 | **Redis single node** | No Sentinel/Cluster. Redis failure causes idempotency to fall-open → duplicate payments possible. | Configure Redis Sentinel (1 primary + 2 replicas). Make DB `ON CONFLICT` the authoritative idempotency guard. |

### Plan Gap P1s

| # | Area | Finding | Priority | Effort |
|---|---|---|---|---|
| G-6 | **Distributed tracing (OTEL)** | Logs have trace ID but no cross-service trace propagation. Impossible to trace a failed payment across 3 services. | P1 | M |
| G-7 | **Balance reconciliation job** | No job verifying `balances.available_amount = SUM(entries)`. Silent data corruption goes undetected. | P1 | M |
| G-8 | **HikariCP tuning** | Default 10-connection pool. 30 concurrent transfers will exhaust it. No pool metrics exposed. | P1 | S |
| G-9 | **Liveness vs readiness probes** | `/health` exists but no `management.endpoint.health.probes.enabled`. K8s needs both for safe rolling deploys. | P1 | S |
| G-10 | **Unit tests for core business logic** | Only integration tests. No unit tests for double-entry, spend limits, optimistic locking. Slow, can't isolate bugs. | P1 | M |

### UX/API P1s

| # | Area | Finding | Recommendation |
|---|---|---|---|
| U-5 | **Transaction history has no amounts** | `TransactionResponse` has no `amount`/`currency` — data is in `entries` table, not joined. HistoryPage shows type/status only. | Join `entries` on `transaction_id` to aggregate debit/credit. Add `amount`, `currency`, `fromAccountId`, `toAccountId` to `TransactionResponse`. |
| U-6 | **REST naming violations** | `POST /transactions/transfer` has verb in URL. `POST /users/register` action-based vs `POST /accounts` resource-based. | Rename: `POST /api/v1/transfers`. Standardise on `POST /api/v1/users` for registration. |
| U-7 | **No transfer confirmation / receipt** | Transfer submits immediately on click (irreversible). Success shows truncated UUID inline with no next step. | Add modal confirmation: "Send $X from Account A to B?". Navigate to `/transfer/receipt/:id` on success. |
| U-8 | **WCAG 2.1 violations** | `<label>` not associated with inputs via `htmlFor`/`id`. `KycBadge` colour-only status (no text). Error alerts not announced to screen readers. | Add `id`+`htmlFor` pairs. Add `aria-label` to badge. Add `role="alert"` to error divs. |
| U-9 | **Swagger UI in production** | `/swagger-ui/**` is `permitAll()` with no env guard. Full API surface publicly documented. | `springdoc.swagger-ui.enabled: ${SWAGGER_ENABLED:false}`. Gate on env var. |

---

## 🟡 P2 — Post-launch Critical

### Security P2s
- BCrypt strength 10 → should be 12 for banking. Weak password policy (`@Size(min=8)` only).
- Timing-unsafe service key comparison (`String.equals` vs constant-time).
- Spring Security session policy not explicitly `STATELESS` (can create JSESSIONID cookies).
- GDPR right to erasure: no `DELETE /users/{id}` endpoint, no PII anonymisation workflow.

### Scaling P2s
- `@Scheduled` thread pool: single thread (default). Adding any second `@Scheduled` task causes head-of-line blocking.
- `SpendCounterCache`: `INCRBY` + `EXPIRE` non-atomic. Crash between commands leaves counter with no TTL → permanent spend block.
- Optimistic locking mismatch: `balances.version` is incremented but `WHERE VERSION = :v` is never in the UPDATE. `SELECT FOR UPDATE` (pessimistic) is the actual strategy; the version field is misleading.
- No production reverse proxy / nginx config. Vite dev proxy does not serve production builds.

### Plan Gap P2s
- Data archival: `entries` and `transactions` grow unbounded. No partition strategy.
- Flyway rollback scripts: forward-only migrations.
- Admin operations: no endpoints to freeze accounts, requeue FAILED outbox.
- Multi-currency FX: cross-currency transfers silently transfer wrong minor-unit amount.
- Load/performance tests: unknown throughput ceiling.

### UX/API P2s
- `GET /api/v1/accounts` has no `?userId=` filter param.
- `GET /api/v1/payments` has no pagination and no user scoping.
- `TransferResponse` missing `amount`, `currency`, `fromAccountId`, `toAccountId`.
- Account type jargon: ASSET/LIABILITY/EQUITY shown to end users.
- Currency field free-text; should be `<select>` of supported currencies.
- Country code free-text; should be ISO 3166-1 `<select>`.
- No mobile responsive breakpoints.
- `Sunset`/`Deprecation` response headers missing.
- No `ETag`/conditional GET for account caching.
- Date serialisation: no explicit ISO 8601 guarantee in OpenAPI.

---

## 🟢 P3 — Nice-to-have
- HATEOAS `_links` in responses.
- API versioning sunset header strategy.
- Localization / i18n (issue #32 planned).
- Feature flags (issues #46, #47 planned).
- Service worker / offline support in frontend.
- Account detail page (`/accounts/:id` with filtered entry history).

---

## Top 10 Issues to Fix First

| Rank | Issue | Why |
|---|---|---|
| 1 | JWT auth + login endpoint (prerequisite for everything) | Unblocks IDOR fix, session, scoping |
| 2 | Outbox: FOR UPDATE in single TX + real dispatcher | Payments silently lost |
| 3 | Transfer idempotency key | Double-debit on retry |
| 4 | Graceful shutdown (`server.shutdown: graceful`) | 2-line fix, prevents data corruption |
| 5 | jOOQ DEBUG log guard | Passwords written to disk right now |
| 6 | Service key empty-default disables filter | Security control silently off |
| 7 | Resilience4j on KycGateway | Cascade failure risk |
| 8 | Redis: Sentinel + auth + SpendCounter atomicity | Correctness + availability |
| 9 | Immutable audit trail (`audit_events` table) | Regulatory compliance |
| 10 | Login page + transfer UX (dropdown accounts, confirmation) | App unusable without it |
