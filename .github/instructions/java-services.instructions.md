---
applyTo: "src/main/java/**/*.java"
---

# Java Service Instructions

## Layer Pattern

```
@RestController  →  @Service (@Transactional)  →  DSLContext (jOOQ)
```

No JPA, no Hibernate, no Spring Data repositories. `DSLContext dsl` is injected directly into service classes.

## jOOQ Usage

Always use generated type-safe constants — never raw SQL strings or string column names:

```java
// ✅ correct
dsl.insertInto(Users.USERS)
   .set(USERS.EMAIL, email)
   .set(USERS.STATUS, "ACTIVE")
   .execute();

// ❌ wrong
dsl.execute("INSERT INTO users (email, status) VALUES (?, ?)", email, "ACTIVE");
```

Generated classes live in `target/generated-sources/jooq/`. Never hand-edit them.

## Money / Amounts

- Store and pass monetary values as `long` (BIGINT minor units — cents/pence)
- Never use `Double`, `Float`, or `BigDecimal` in the DB or service layer
- Document units in variable names: `long amountInCents`

## DTOs

All request/response objects are **Java records**, placed in the `dto/` package:

```java
// ✅
public record TransferRequest(UUID fromAccountId, UUID toAccountId, long amount, String currency) {}

// ❌ — don't use classes with fields
public class TransferRequest { private UUID fromAccountId; ... }
```

## Exception Handling

Use domain exceptions from the `common` module — never throw raw `RuntimeException` for business errors:

| Exception | HTTP | When |
|---|---|---|
| `ConflictException` | 409 | Duplicate resource (e.g. duplicate email) |
| `NotFoundException` | 404 | Entity doesn't exist |
| `UnprocessableException` | 422 | Business rule violation (e.g. insufficient funds) |
| `IllegalArgumentException` | 400 | Invalid input |

`GlobalExceptionHandler` (from `common` auto-configuration) converts these to RFC 7807 `ProblemDetail` responses automatically.

## Transactions

- `@Transactional` is on the **service** layer, not the controller
- Default isolation is PostgreSQL `READ COMMITTED` — sufficient for most operations
- For balance updates requiring pessimistic locking, use `dsl.selectFrom(...).forUpdate()`
- After a jOOQ UPDATE, check the returned row count: `== 0` means either contention or a failed precondition

## JSONB Fields

Use `JSONB.valueOf(jsonString)` when inserting JSONB values:

```java
.set(USERS.FLAGS, JSONB.valueOf("{\"kyc_verified\": false}"))
```

## Common Module

`common` contains shared beans auto-loaded via Spring Boot auto-configuration:
- `GlobalExceptionHandler` — RFC 7807 error responses
- `TraceIdFilter` — injects `X-Trace-Id` into MDC
- `RequestLoggingFilter` — logs method, URI, status, duration

Services register filters with their own name via `WebFilterConfig` in the `config/` package.
