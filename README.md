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

- **Double-Entry Accounting** — every transfer creates two `entries` rows (debit negative, credit positive) within one `transactions` record
- **Optimistic Locking** — `balances.version` is incremented atomically; zero rows updated = insufficient funds or concurrent conflict
- **Transactional Outbox** — `payment_outbox` is written in the same DB transaction as `payment_orders`, guaranteeing at-least-once event delivery
- **No JPA** — all DB access uses jOOQ's type-safe DSL; generated table/column constants enforce compile-time schema safety
- **Minor units** — all monetary amounts are stored as `BIGINT` (cents), never `Double` or `BigDecimal`

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
