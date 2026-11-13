# Copilot Instructions — NeoBank Workshop

## Architecture

Multi-module Maven project (Java 21, Spring Boot 4.0.6) consisting of three independent microservices, each with its own PostgreSQL database:

| Service | Port | Database | Responsibility |
|---|---|---|---|
| `user-service` | 8081 | `neobank_user_db` | Identity, registration, login (JWT), KYC |
| `ledger-service` | 8082 | `neobank_ledger_db` | Double-entry accounting, balances, transfer idempotency |
| `payment-service` | 8083 | `neobank_payment_db` | Payment orders, Outbox pattern |

Services reference each other only via `user_id` UUID — there are no cross-database foreign keys. The root `pom.xml` is the parent aggregator for these three modules. `core-engine/` is a separate standalone Spring Boot module with its own `mvnw` (not part of the parent build — build it with `cd core-engine && ./mvnw verify`). `frontend/` is a React 18 + Vite client (plain JS/JSX) built with npm, outside Maven — see `.github/instructions/frontend.instructions.md`.

## Git Workflow

- **Commit message convention**: `WSNB - <message>` (no Co-authored-by trailer)
- **Never commit directly to `main`** — always create a feature branch and open a PR:
  ```bash
  git checkout -b phase-X-<description>
  # ... do work, commit ...
  git push -u origin phase-X-<description>
  gh pr create --fill
  ```

## Build & Run

```bash
# PostgreSQL 16 + Redis 7 (same versions as docker-compose.yml and CI).
# Use local Homebrew services first; fall back to Docker:
#   brew services start postgresql@16 && brew services start redis   ← preferred
#   docker compose up -d                                              ← fallback

# Build all modules
./mvnw clean install

# Build a single module (skipping tests)
./mvnw clean install -pl user-service -DskipTests

# Run all tests in a module
./mvnw test -pl ledger-service

# Run a single test class
./mvnw test -pl payment-service -Dtest=PaymentServiceTest

# Apply Flyway migrations for a service
./mvnw flyway:migrate -pl user-service

# Regenerate jOOQ sources (requires DB to be up and migrations applied)
./mvnw generate-sources -pl user-service
```

## Key Conventions

### jOOQ (no JPA/Hibernate)
All database access uses jOOQ's `DSLContext` — there is no JPA, Hibernate, or Spring Data. Generated classes live in `target/generated-sources/jooq/<package>/jooq/` and are regenerated from the live database schema. Never hand-edit generated files.

The standard layer pattern is:
```
@RestController → @Service (@Transactional) → DSLContext
```

Services inject `DSLContext dsl` directly. Use the typed jOOQ table/column constants (e.g., `PaymentOrders.PAYMENT_ORDERS`, `Balances.BALANCES.VERSION`) instead of raw SQL strings.

### Monetary Amounts
All monetary values are stored as `BIGINT` in **minor units** (cents/pence). Never use `Double` or `BigDecimal` in the DB layer.

### Database Schema Changes
1. Add a new Flyway migration file in `src/main/resources/db/migration/` using the naming convention `V{N}__{description}.sql` (e.g., `V2__add_user_kyc_flag.sql`).
2. Run `./mvnw flyway:migrate -pl <service>` to apply.
3. Run `./mvnw generate-sources -pl <service>` to regenerate jOOQ classes.

### DTOs
All request/response DTOs are Java records (not classes). Place them in the `dto/` package of the relevant service.

### Balance Locking
Transfers lock the source `balances` row with `SELECT … FOR UPDATE` (pessimistic) before the sufficient-funds check; the debit/credit UPDATEs then increment `balances.version`. A credit UPDATE that touches `0` rows means the destination balance row is missing and the transaction rolls back.

### Outbox Pattern
Payment service writes to `payment_outbox` in the same transaction as `payment_orders`. Events have a `status` of `PENDING` until processed. Use `idx_payment_outbox_status` index for polling queries. `OutboxPoller` runs every `outbox.poll.interval-ms` (default 5000, fixed delay), handles up to 10 events per run with one transaction per event and `SELECT … FOR UPDATE SKIP LOCKED`, backs off exponentially (`outbox.poll.retry-base-delay-seconds`, default 30 s) and marks events `FAILED` after `outbox.poll.max-retries` (default 3).

### Double-Entry Accounting
In the ledger `entries` table: **negative** amounts = debit (outflow), **positive** amounts = credit (inflow). Every transfer creates exactly two `entries` rows within the same `transactions` record.

### JSONB Fields
Several tables use JSONB for extensibility: `users.flags` (feature flags), `payment_orders.destination_details` (routing data), `transactions.metadata`, `user_settings.custom_settings`. Use `JSONB.valueOf(...)` from jOOQ when inserting.

### Enums Over Magic Strings
Status values (`PENDING`, `APPROVED`, `REJECTED`, etc.) must be Java enums, even when stored as plain strings in the database. Shared status enums live in the `common` module under `com.neobank.common.model`. Convert at the DB boundary: `KycStatus.valueOf(dbString)` on read, `.name()` on write. Never use raw string comparisons like `"APPROVED".equals(status)`.

### Config Defaults Belong in `application.yml`
Use `@Value("${my.prop}")` — never `@Value("${my.prop:hardcoded-default}")`. The `application.yml` is the single source of truth for defaults; inline fallbacks in annotations make defaults invisible to operators and inconsistent across environments.

### No Magic Numbers or Strings
Extract any literal used more than once (timeouts, limits, retry intervals, status codes, path patterns) as a named `private static final` constant at the top of the class. Example: `RETRY_AFTER_SECONDS = 60` referenced by both the `Retry-After` header and the window calculation.

### Java Text Blocks for Multi-Line Strings
Use `"""..."""` text blocks for inline JSON, multi-line error messages, or any string spanning multiple lines. Never use `+` string concatenation across lines.

### Tests Are Mandatory
Every code change must include or update integration tests:
- New service method → happy path test + at least one error case
- Bug fix → regression test that would have caught the bug
- New exception mapping → assert exact status code and `$.code` field
Run `./mvnw test -pl <service>` locally before pushing.

### Document All Logic
Public classes and non-trivial methods must have Javadoc explaining *why*, not just *what*. Multi-step logic (e.g. the 14-step transfer order in `LedgerService`) must have numbered inline comments. Never leave `// TODO` or vague placeholder comments in committed code.

## Database Credentials (local dev)

```
Host:     localhost:5432
User:     neouser
Password: neopassword
Databases: neobank_user_db | neobank_ledger_db | neobank_payment_db
```

Credentials are configured in each service's `src/main/resources/application.yml` and mirrored in the Flyway/jOOQ Maven plugin configuration in `pom.xml`.

### Transfer Idempotency
`POST /api/v1/transactions/transfer` accepts an optional `Idempotency-Key` header. `LedgerService` checks Redis first (`TransferIdempotencyCache`, key `transfer-idem:{key}`, TTL 24 h); the authoritative guard is the `UNIQUE` constraint `uq_transactions_idempotency_key` on `transactions.idempotency_key` with `ON CONFLICT DO NOTHING`, which returns the existing transaction. The Redis key is written only after the DB commit.

## JWT Authentication

- **Login**: `POST /api/v1/users/login` (`LoginRequest {email, password}`) → `LoginResponse {accessToken, userId, email, expiresIn}`. Bad credentials → 401 (`UnauthorizedException`), suspended user → 403.
- **Token issuing**: `JwtUtil` in `user-service` (`com.neobank.userservice.service.JwtUtil`) signs HS256 tokens: `sub` = userId, claim `email`, expiry `jwt.expiration-hours` (default 1). Secret: `jwt.secret` / env `JWT_SECRET` (≥ 256 bits, identical in all services).
- **Dependency**: jjwt 0.13.x — `jjwt-api` (compile) plus `jjwt-impl` and `jjwt-jackson` (runtime) in `user-service` and `common`. Use the 0.12+ API (`Jwts.builder().subject(...)`, `Jwts.parser().verifyWith(key)`, `Jwts.SIG.HS256`).
- **Authenticated requests**: clients send `Authorization: Bearer <accessToken>`. ledger-service and payment-service register `common`'s `BearerTokenFilter` on `/api/*` (`WebConfig`); it sets request attributes `userId` and `userEmail` on a valid token and returns 401 on an invalid/expired one. Requests without the header currently pass through.
- **user-service `SecurityConfig`**: `permitAll` for `/health`, `/api/v1/users/register`, `/api/v1/users/login`, `/api/v1/users/*/kyc-status`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`; `anyRequest().authenticated()` otherwise. CSRF disabled; CORS from `cors.allowed-origins`. No bearer-token filter is wired into this chain yet.
- **Internal calls**: `/kyc-status` is additionally guarded by `InternalServiceKeyFilter` (`X-Service-Key` header, `INTERNAL_SERVICE_KEY`) when the key is configured.

### Exception Handling
A shared `GlobalExceptionHandler` in the `common` module is auto-configured into all services via Spring Boot autoconfiguration. It returns RFC 7807 `ProblemDetail` responses:
- `ConflictException` → 409, `code=CONFLICT`
- `NotFoundException` → 404, `code=NOT_FOUND`
- `UnprocessableException` → 422, `code=UNPROCESSABLE`
- `ForbiddenException` → 403, `code=FORBIDDEN`
- `UnauthorizedException` → 401, `code=UNAUTHORIZED`
- `ServiceUnavailableException` → 503, `code=SERVICE_UNAVAILABLE`
- `IllegalArgumentException` → 400, `code=BAD_REQUEST`
- `RuntimeException` → 500, `code=INTERNAL_ERROR`

## Testing Conventions (Spring Boot 4)

- `TestRestTemplate` and `@AutoConfigureMockMvc` are **removed** in Spring Boot 4.
- Use `MockMvcBuilders.webAppContextSetup(context).build()` in `@BeforeEach`.
- Spring Boot 4 uses **Jackson 3.x** (`tools.jackson.core:jackson-databind`). Import `ObjectMapper` from `tools.jackson.databind.ObjectMapper` and inject it with `@Autowired` — do not construct it manually.
- Test classes use `@SpringBootTest` (full context) against the local PostgreSQL.
- Tests are **not** isolated per-run — use unique emails/references (e.g., `UUID.randomUUID()`) to avoid conflicts across runs.

## CI / GitHub Actions

- `ci.yml`: starts PostgreSQL service container, creates 3 databases, runs Flyway migrations × 3, then `./mvnw verify`.
- `codeql.yml`: same DB setup, runs security-and-quality queries weekly.
- `mvnw` must be executable — CI has `chmod +x mvnw` step (macOS commits it as `100644`).
- jOOQ generated sources live in `target/` (gitignored) — CI must run Flyway + compile to regenerate them.
- Maven env var defaults: use `<properties>` block in `pom.xml` (`<db.host>localhost</db.host>`) — Maven does **not** support `${env.VAR:default}` syntax (only Spring Boot does).
- Only `neobank_user_db` is created by the PostgreSQL service container's `POSTGRES_DB`; the other two must be created via `psql -c "CREATE DATABASE ..."` before Flyway runs.
