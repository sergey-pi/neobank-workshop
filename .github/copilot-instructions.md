# Copilot Instructions — NeoBank Workshop

## Architecture

Multi-module Maven project (Java 21, Spring Boot 4.0.6) consisting of three independent microservices, each with its own PostgreSQL database:

| Service | Port | Database | Responsibility |
|---|---|---|---|
| `user-service` | 8081 | `neobank_user_db` | Identity, registration, KYC |
| `ledger-service` | 8082 | `neobank_ledger_db` | Double-entry accounting, balances |
| `payment-service` | 8083 | `neobank_payment_db` | Payment orders, Outbox pattern |

Services reference each other only via `user_id` UUID — there are no cross-database foreign keys. The root `pom.xml` is the parent aggregator for these three modules. `core-engine/` is a separate standalone Spring Boot module (not part of the parent build).

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
# PostgreSQL priority: use local Homebrew PostgreSQL first.
# If not available, fall back to Docker:
#   brew services start postgresql@18   ← preferred
#   docker compose up -d                ← fallback

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

### Optimistic Locking
`balances.version` in the ledger service is used for optimistic locking. Updates check `AVAILABLE_AMOUNT >= requested_amount` and increment `VERSION` atomically; a return of `0` updated rows means contention or insufficient funds.

### Outbox Pattern
Payment service writes to `payment_outbox` in the same transaction as `payment_orders`. Events have a `status` of `PENDING` until processed. Use `idx_payment_outbox_status` index for polling queries.

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
Public classes and non-trivial methods must have Javadoc explaining *why*, not just *what*. Multi-step logic (e.g. the 9-step transfer order) must have numbered inline comments. Never leave `// TODO` or vague placeholder comments in committed code.

## Database Credentials (local dev)

```
Host:     localhost:5432
User:     neouser
Password: neopassword
Databases: neobank_user_db | neobank_ledger_db | neobank_payment_db
```

Credentials are configured in each service's `src/main/resources/application.yml` and mirrored in the Flyway/jOOQ Maven plugin configuration in `pom.xml`.

### Exception Handling
A shared `GlobalExceptionHandler` in the `common` module is auto-configured into all services via Spring Boot autoconfiguration. It returns RFC 7807 `ProblemDetail` responses:
- `ConflictException` → 409, `code=CONFLICT`
- `NotFoundException` → 404, `code=NOT_FOUND`
- `UnprocessableException` → 422, `code=UNPROCESSABLE`
- `IllegalArgumentException` → 400
- `RuntimeException` → 500

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
