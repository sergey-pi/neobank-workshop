# NeoBank Workshop — Agent Quick-Start

Multi-module Maven project (Java 21, Spring Boot 4.0.6). Three independent microservices, each with its own PostgreSQL database.

| Service | Port | DB |
|---|---|---|
| `user-service` | 8081 | `neobank_user_db` |
| `ledger-service` | 8082 | `neobank_ledger_db` |
| `payment-service` | 8083 | `neobank_payment_db` |

Shared cross-cutting code lives in the `common` Maven module.
Full details: [`.github/copilot-instructions.md`](.github/copilot-instructions.md)

---

## Essential Commands

```bash
# 1. Start PostgreSQL first (required for everything below)
brew services start postgresql@16    # macOS Homebrew
# OR: docker compose up -d

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
