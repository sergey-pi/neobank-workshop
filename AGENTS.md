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
