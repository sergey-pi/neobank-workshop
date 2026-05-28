# NeoBank scaling and partitioning plan

Scope: `/Users/spashuk/Documents/ppp/workshop` as of the current MVP. This plan is grounded in `architecture_c4.puml`, `docs/production-readiness.md`, and the V1 Flyway schemas for `user-service`, `ledger-service`, and `payment-service`, plus the current `LedgerService`, `OutboxPoller`, Redis caches, and controller wiring.

---

## 0. Current state snapshot

| Area | Current implementation | Scaling implication |
|---|---|---|
| Service state | All services are stateless; JWT is used and Redis stores shared caches/counters | Good base for horizontal scaling |
| Database access | One PostgreSQL primary per service DB, single `spring.datasource`, single injected `DSLContext` | Reads and writes compete on the same primary |
| Ledger write path | `LedgerService` debits/credits inside one DB transaction; locks `balances` rows and inserts into `transactions` + `entries` | Hot-account contention becomes the first hard limit |
| Payment async path | `OutboxPoller` scans `payment_outbox` and calls `PaymentEventPublisher`; default publisher only logs | Polling overhead and weak fan-out at higher volume |
| Rate limiting | `user-service` uses in-memory Caffeine `RateLimiterFilter` | Does not work correctly across multiple instances |
| Redis | Single node for idempotency / KYC / spend cache | Redis is a shared SPOF |
| API edge | No dedicated external gateway in the deployed MVP | Auth, rate limit, TLS, and balancing are repeated in services |

ASCII view of the next-stage target:

```text
Clients
  |
[API Gateway / WAF]
  |
  +--> user-service  --> Postgres primary + read replicas
  +--> ledger-service --> Postgres primary + read replicas + partitioned history tables
  +--> payment-service -> Postgres primary + read replicas + partitioned outbox/orders
                         |
                         +--> Kafka
  |
 Redis Sentinel/Cluster
```

---

## 1. Current bottlenecks analysis

### 1.1 Fastest-growing tables

| Service | Table | Why it grows fast | Expected scale behavior |
|---|---|---|---|
| ledger-service | `transactions` | One row per transfer/top-up/withdrawal | Reaches 100M+ rows first |
| ledger-service | `entries` | Two rows minimum per financial transaction | Will outgrow every other table; billions of rows are realistic |
| payment-service | `payment_orders` | One row per payment request | High growth under card/SEPA/SWIFT traffic |
| payment-service | `payment_outbox` | One or more rows per order/event | Polling and retention become expensive |
| user-service | `devices`, `user_addresses`, audit-like user tables | Moderate growth | Manageable on a single instance far longer |
| ledger-service | `balances` | One row per account | Small/hot table, not a history table |

### 1.2 Operations that become contention points

1. **Ledger balance mutation is the critical bottleneck**
   - `LedgerService` currently locks the source balance row with `SELECT ... FOR UPDATE` and then updates balances inside one transaction.
   - The schema also carries a `balances.version` column, so the intended concurrency model is clearly hot-row protection.
   - Result: popular accounts become serialization points. At high concurrency, either lock waits or optimistic-lock retries will spike.

2. **Daily spend validation gets more expensive with history growth**
   - The authoritative check sums `entries` for the account for the current day.
   - Without a date-friendly index/partition layout, this becomes slower as `entries` becomes huge.

3. **Outbox polling is CPU/IO heavy at scale**
   - `OutboxPoller` loops every few seconds and uses `FOR UPDATE SKIP LOCKED` correctly for multi-instance workers.
   - This scales for a while, but constant polling burns DB cycles and keeps the primary involved in work that should move to a log/event bus.

4. **Primary-only reads will starve writes**
   - Current controllers (`AccountController`, `TransactionController`, `PaymentController`, `UserController`) all read through the single primary `DSLContext`.
   - Heavy `GET /accounts`, `GET /transactions`, `GET /payments`, and admin/user queries will compete with writes.

5. **In-memory rate limiting breaks under horizontal scale**
   - `RateLimiterFilter` uses local Caffeine memory, so 5 pods effectively allow ~5x the intended traffic.

### 1.3 Single points of failure in the current design

| SPOF | Current state | Impact |
|---|---|---|
| PostgreSQL primary per service | Single writer and single reader target | DB node failure or saturation degrades entire service |
| Redis single node | Shared for idempotency, KYC cache, spend counters | Cache loss, duplicate acceptance, spend guard degradation |
| Payment event dispatch | Default `LoggingPaymentEventPublisher` only logs | No durable downstream event fan-out |
| API edge | No dedicated gateway tier for auth/TLS/rate limiting/load balance | Concerns duplicated in-app; harder failover |
| Regional deployment | Single-region mindset | DR and latency limitations |

**Bottom line:** the first scaling wall is not CPU in Spring Boot; it is **database contention and unbounded history tables**, especially `ledger.transactions`, `ledger.entries`, `payment_orders`, and `payment_outbox`.

---

## 2. PostgreSQL table partitioning

## 2.1 Recommendation summary

| Table | Recommendation | Why |
|---|---|---|
| `ledger.transactions` | `PARTITION BY RANGE (created_at)` monthly | Large append-heavy history; easy pruning/archive |
| `ledger.entries` | `PARTITION BY RANGE (created_at)` monthly | Largest table in system; partition pruning is essential |
| `payment.payment_orders` | `PARTITION BY RANGE (created_at)` monthly | History scans and retention management |
| `payment.payment_outbox` | `PARTITION BY RANGE (created_at)` monthly | Polling queries stay small when old partitions are archived |
| `balances` | **Do not partition** | Small hot current-state table; partitioning hurts more than it helps |
| user-service core tables | No partitioning yet | Growth profile is lower; keep simple |

**Monthly vs quarterly:**
- Use **monthly partitions** by default.
- Move to **quarterly** only if partition count becomes operationally noisy and traffic is much lower than expected.

## 2.2 Ledger partition design

### Parent tables

```sql
CREATE TABLE transactions_new (
    id UUID NOT NULL,
    reference VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at),
    UNIQUE (reference, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE entries_new (
    id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    type VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
```

### Example monthly partitions

```sql
CREATE TABLE transactions_2026_01 PARTITION OF transactions_new
FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-02-01 00:00:00+00');

CREATE TABLE entries_2026_01 PARTITION OF entries_new
FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-02-01 00:00:00+00');

CREATE INDEX idx_transactions_2026_01_created_at ON transactions_2026_01 (created_at DESC);
CREATE INDEX idx_entries_2026_01_account_created_at ON entries_2026_01 (account_id, created_at DESC);
CREATE INDEX idx_entries_2026_01_tx_id ON entries_2026_01 (transaction_id);
```

### Why these indexes matter
- `TransactionController#getTransactions()` filters by account ownership via `entries -> accounts -> transactions` and orders by `transactions.created_at DESC`.
- Partition-local indexes keep hot recent partitions small and cache-friendly.
- The daily spend query benefits from `(account_id, created_at)`.

## 2.3 Payment partition design

```sql
CREATE TABLE payment_orders_new (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    destination_details JSONB NOT NULL,
    external_reference VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at),
    UNIQUE (idempotency_key, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE payment_outbox_new (
    id UUID NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
```

Per-partition indexes:

```sql
CREATE INDEX idx_payment_orders_2026_01_user_created_at
ON payment_orders_2026_01 (user_id, created_at DESC);

CREATE INDEX idx_payment_outbox_2026_01_status_created_at
ON payment_outbox_2026_01 (status, created_at)
WHERE status = 'PENDING';
```

## 2.4 Retention and cold storage

**Policy:** archive partitions older than **2 years** to cold storage.

Suggested process:
1. Freeze old partition (`ALTER TABLE ... DETACH PARTITION`).
2. Export to Parquet/CSV for S3/GCS cold storage.
3. Retain checksum + manifest for audit.
4. Drop detached partition from primary database after validation.

This keeps the hot OLTP dataset small while preserving regulatory evidence.

## 2.5 Automation with pg_partman

Use `pg_partman` to pre-create partitions and manage retention.

```sql
CREATE EXTENSION IF NOT EXISTS pg_partman;

SELECT partman.create_parent(
    p_parent_table := 'public.entries_new',
    p_control := 'created_at',
    p_type := 'native',
    p_interval := 'monthly'
);

SELECT partman.create_parent(
    p_parent_table := 'public.transactions_new',
    p_control := 'created_at',
    p_type := 'native',
    p_interval := 'monthly'
);
```

Run `partman.run_maintenance_proc()` from a scheduled job.

## 2.6 Migration strategy in this codebase

Add Flyway migrations:
- `ledger-service/src/main/resources/db/migration/V{N}__partition_transactions_entries.sql`
- `payment-service/src/main/resources/db/migration/V{N}__partition_payment_orders_outbox.sql`

Recommended rollout:
1. Create new partitioned parent tables.
2. Create next 6-12 monthly partitions.
3. Backfill old data in batches ordered by `created_at`.
4. Swap names during a maintenance window.
5. Regenerate jOOQ sources with `./mvnw generate-sources -pl ledger-service,payment-service`.
6. Verify all jOOQ-generated table metadata points to the new partitioned parents.

**Important:** do not partition `balances`; instead add/keep targeted indexes and keep the table in RAM as much as possible.

---

## 3. Read replica strategy

## 3.1 Routing model

| Operation type | Target |
|---|---|
| Transfers, account creation, payments, registration, login | Primary only |
| `GET /accounts`, `GET /transactions`, `GET /payments`, backoffice/user lookups | Replica pool |
| Read-after-write paths | Primary for a short stickiness window |

## 3.2 Spring implementation approach

Current state: each service has a single `spring.datasource` and one injected `DSLContext`.

Target state:
- Add `primaryDataSource`
- Add `replicaDataSource`
- Route with `AbstractRoutingDataSource`
- Build jOOQ on top of the routed datasource
- Use `@Transactional(readOnly = true)` for read services

### Routing context

```java
public final class ReadWriteContext {
    private static final ThreadLocal<Boolean> READ_ONLY = ThreadLocal.withInitial(() -> false);

    public static void markReadOnly(boolean readOnly) { READ_ONLY.set(readOnly); }
    public static boolean isReadOnly() { return READ_ONLY.get(); }
    public static void clear() { READ_ONLY.remove(); }
}
```

### Routing datasource

```java
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return ReadWriteContext.isReadOnly() ? "replica" : "primary";
    }
}
```

### Transaction hook

```java
@Aspect
@Component
public class ReadOnlyTransactionRoutingAspect {
    @Before("@annotation(tx)")
    public void before(Transactional tx) {
        ReadWriteContext.markReadOnly(tx.readOnly());
    }

    @After("@annotation(tx)")
    public void after(Transactional tx) {
        ReadWriteContext.clear();
    }
}
```

### jOOQ bean wiring

```java
@Bean
public DSLContext dslContext(DataSource routingDataSource) {
    return DSL.using(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(routingDataSource)), SQLDialect.POSTGRES);
}
```

## 3.3 What to change in this repo

1. Add a config class per service, e.g.:
   - `ledger-service/.../config/ReadReplicaDataSourceConfig.java`
   - `payment-service/.../config/ReadReplicaDataSourceConfig.java`
   - `user-service/.../config/ReadReplicaDataSourceConfig.java`
2. Move controller reads into service classes annotated with `@Transactional(readOnly = true)`.
   - Example: `AccountController#getAccounts()` should call `AccountQueryService`.
   - Example: `TransactionController#getTransactions()` should call `TransactionQueryService`.
   - Example: `PaymentController#getPayments()` should call `PaymentQueryService`.
3. Keep write services (`LedgerService`, `PaymentService`, `UserService`) on primary transactions.

## 3.4 Replication lag handling

Use one of these patterns:

| Pattern | Recommendation |
|---|---|
| Sticky primary for 1 second after write | Best first step |
| `read-your-write` cookie/header | Good for frontend/API gateway |
| Force specific endpoints to primary | Use for payment/transfer confirmation screens |

Practical rule:
- After `POST /transactions/transfer` or `POST /payments`, route the immediate follow-up `GET` to primary for ~1 second.

---

## 4. Horizontal service scaling

## 4.1 Stateless services

All 3 services are already close to horizontally scalable because they do not rely on local HTTP session state.

| Service | Current status | Scaling note |
|---|---|---|
| user-service | JWT + Redis cache | Scale freely after rate limiter moves to Redis |
| payment-service | Stateless request handling + outbox poller | Multi-instance safe for polling due to `SKIP LOCKED` |
| ledger-service | Stateless app layer, state concentrated in DB rows | App instances scale, but DB hot rows limit throughput |

## 4.2 Ledger-service: main scale challenge

**Why it is hardest:** every money movement converges on a few hot rows in `balances`.

### Symptoms at scale
- lock wait time rises
- version-retry rates rise if optimistic locking is enforced later
- p95/p99 transfer latency spikes on popular accounts
- throughput flattens long before CPU saturation

### Option 1: account-level sharding in the application tier

Route all operations for the same `account_id` to the same ledger instance.

```text
hash(account_id) % N -> ledger instance group
```

Benefits:
- fewer competing workers per hot account
- simpler than full database sharding

Code changes:
- introduce a request router at gateway/service-mesh level keyed by `account_id`
- keep each account’s operations sticky to one instance group
- preserve DB transaction logic inside that instance

### Option 2: queue-based serialization per account

Use Redis/Kafka keyed by `account_id` and process one worker stream per account key.

Benefits:
- strongest protection against hot-row storms
- natural ordering for repeated debits on same account

Trade-off:
- higher architecture complexity
- more async orchestration for transfer submission and completion state

### Recommendation

**Near term:** keep the current synchronous transfer API, but add:
1. better balance-row monitoring,
2. retry/backoff metrics,
3. shard-aware routing at the gateway if hotspot accounts appear.

**Later:** move hot-account flows to queue-based serialization only if contention becomes the limiting factor.

## 4.3 OutboxPoller contention

Current `OutboxPoller` uses `FOR UPDATE SKIP LOCKED`, which is the correct multi-instance polling pattern.

Recommendation:
- scale `payment-service` instances freely for now;
- keep `BATCH_SIZE` and poll interval configurable;
- move to Kafka when sustained event throughput exceeds what polling can handle efficiently.

## 4.4 Rate limiter migration

Current state: `user-service` `RateLimiterFilter` is in-memory.

Target state: Redis-backed sliding window or token bucket using Lua.

```lua
-- KEYS[1] = rate-limit key
-- ARGV[1] = now millis
-- ARGV[2] = window start millis
-- ARGV[3] = limit
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2])
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[1])
local count = redis.call('ZCARD', KEYS[1])
redis.call('PEXPIRE', KEYS[1], 120000)
return count
```

What to change:
- replace the internal Caffeine structure inside `RateLimiterFilter`
- optionally move rate limiting entirely to the API gateway (preferred)
- reuse Redis already present in the project

---

## 5. Sharding strategy

## 5.1 Option comparison

| Option | Shard key | Pros | Cons | Verdict |
|---|---|---|---|---|
| A. User-ID based sharding | `hash(user_id)` or `user_id % N` | Keeps most of a customer’s data together; natural for retail banking | Cross-user transfers can cross shards | **Recommended** |
| B. Account-ID based sharding | `hash(account_id)` | Simple for ledger tables only | Splits user context; worse for account aggregation | Not recommended |

## 5.2 Recommended option: user-ID based sharding

Why it fits NeoBank:
- most queries are customer-centric
- account listing, payment history, settings, KYC, and most support lookups cluster around one user
- co-location reduces cross-shard joins for everyday reads

Potential implementation choices:
1. **Citus** on PostgreSQL for distributed tables
2. consistent hashing at the application/platform layer

### Citus example direction

```sql
SELECT create_distributed_table('accounts', 'user_id');
SELECT create_distributed_table('balances', 'account_id');
SELECT create_reference_table('supported_currencies');
```

### Cross-shard transfer challenge

When sender and recipient live on different shards, you need:
- 2-phase commit, or
- a safer banking-style **saga** with compensating actions and immutable event trail

For this project, favor saga/orchestrated transfer states over distributed XA.

## 5.3 Recommendation threshold

**Do not shard yet.**

Stay on a single PostgreSQL writer per service until one of these becomes true:
- `transactions` > 100M rows and still growing rapidly
- `entries` is in the billions
- >10M users
- >10K concurrent users on peak paths
- read replicas + partitioning are no longer enough

**Recommended order:**
1. partitioning
2. read replicas
3. Redis HA
4. Kafka
5. gateway
6. only then sharding/Citus

This is the simplest path to a realistic **10x capacity improvement** before the team accepts distributed transaction complexity.

---

## 6. Event-driven architecture (Kafka)

## 6.1 Why Kafka is the next step

Current state:
- `payment-service` writes `payment_outbox`
- `OutboxPoller` wakes up every few seconds
- `PaymentEventPublisher` defaults to `LoggingPaymentEventPublisher`

Target state:
- keep the outbox table for transactional safety
- publish durable events to Kafka
- let downstream consumers scale independently

## 6.2 Topic design

| Topic | Producer | Key | Purpose |
|---|---|---|---|
| `payment-events` | payment-service | `user_id` or `aggregate_id` | payment lifecycle |
| `ledger-events` | ledger-service | `account_id` or `user_id` | balance/account/transfer events |
| `user-events` | user-service | `user_id` | KYC/profile/auth events |

**Recommendation:** partition by `user_id` whenever present.
- preserves ordering per customer
- distributes load naturally
- aligns with the recommended future sharding key

## 6.3 Migration path in this codebase

The interface already exists:
- `payment-service/.../service/PaymentEventPublisher.java`
- current impl: `LoggingPaymentEventPublisher`

### Step 1: add Kafka producer dependency
- add `spring-kafka` to `payment-service/pom.xml`

### Step 2: introduce a production publisher

```java
@Component
@ConditionalOnProperty(name = "payments.kafka.enabled", havingValue = "true")
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {
    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @Override
    public void publish(PaymentOutboxRecord record) {
        PaymentEvent event = map(record);
        kafkaTemplate.send("payment-events", event.userId().toString(), event);
    }
}
```

### Step 3: keep the outbox poller, but make it publish to Kafka
- `OutboxPoller` remains the bridge from DB outbox to Kafka initially
- mark row `PROCESSED` only after Kafka publish succeeds

### Step 4: optional Debezium CDC

Alternative long-term path:
- capture `payment_outbox` directly from PostgreSQL WAL via Debezium
- stream to Kafka without active polling
- reduces polling load and publish latency

## 6.4 Break-even guidance

Use Kafka when sustained throughput reaches **>1K events/sec** or when multiple downstream consumers need the same event stream.

---

## 7. Redis scaling

## 7.1 Current Redis usage in the project

| Area | Key pattern | Service |
|---|---|---|
| Transfer idempotency | `transfer-idem:*` | ledger-service |
| Daily spend counter | `spend:{accountId}:{date}` | ledger-service |
| KYC cache | `kyc-status:*` | user-service |
| Payment idempotency | `payment-idem:*` | payment-service |

## 7.2 Scaling path

### Stage 1: Redis Sentinel
- 1 primary + 2 replicas
- automatic failover
- minimal code change versus standalone Redis

### Stage 2: Redis Cluster
- 6 nodes: 3 primaries + 3 replicas
- hash-slot partitioning for horizontal scale

## 7.3 Key design notes

| Key | Cluster note |
|---|---|
| `transfer-idem:<key>` | fine as-is |
| `payment-idem:<key>` | fine as-is |
| `spend:{accountId}:{date}` | hash tag keeps the compound key in one slot |
| rate-limit keys | use one key per principal/IP; Lua script remains atomic per key |

## 7.4 What to change in code

1. Add Redis topology config to each `application.yml`.
2. Make cache code topology-agnostic by using Spring Data Redis configuration only.
3. Replace multi-command sequences with Lua where correctness matters.

### Spend counter fix

Current `SpendCounterCache` does `INCRBY` and `EXPIRE` separately.

Replace with Lua:

```lua
local value = redis.call('INCRBY', KEYS[1], ARGV[1])
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return value
```

This prevents immortal counters if the process crashes between commands.

### Rate limiter fix
- use a Lua sliding window / token bucket
- or remove app-level rate limiting after API gateway rollout

---

## 8. API gateway layer

Add **Kong** or **AWS API Gateway** in front of all services.

## 8.1 Responsibilities

| Concern | Today | Gateway target |
|---|---|---|
| JWT validation | `BearerTokenFilter` in services | validate once at edge |
| Rate limiting | `RateLimiterFilter` in `user-service` only | centralized policy |
| TLS termination | ad hoc / external infra | standard edge responsibility |
| Load balancing | per-service only | gateway distributes to healthy instances |
| Circuit breaking | mostly in-app only | edge-level protection for clients |

## 8.2 What to change in this codebase

1. Keep service-level JWT support initially for defense in depth.
2. Once gateway is stable, simplify:
   - keep internal auth headers propagated by gateway
   - reduce duplicate edge logic in `WebConfig` / servlet filters
3. Add gateway manifests/config under infrastructure deployment assets (not inside core business modules).

Example Kong path policy:

```yaml
services:
  - name: ledger-service
    url: http://ledger-service:8082
    routes:
      - name: ledger-route
        paths: [/api/v1/accounts, /api/v1/transactions]
plugins:
  - name: jwt
  - name: rate-limiting
  - name: correlation-id
```

---

## 9. Multi-region strategy

## 9.1 Recommended progression

### Phase 1: active-passive
- one primary region handles all writes
- second region is warm DR with replicated PostgreSQL and Redis
- DNS or global load balancer fails over on regional outage

### Phase 2: active-active by geography
- users/accounts pinned to a home region
- EU customers stay on EU data plane
- UK/EU/US segmentation aligns with residency and latency

## 9.2 Constraints specific to banking
- GDPR and PSD2 data residency
- audit trail durability
- difficult cross-region transfer semantics
- stricter reconciliation requirements during failover

## 9.3 Database recommendation
- PostgreSQL streaming replication for DR initially
- async cross-region replicas first
- only consider multi-writer patterns after very high maturity

---

## 10. Capacity estimates

These are planning numbers, not guarantees. Validate with k6/Gatling + production-like PostgreSQL benchmarks.

| Layer | Rough capacity |
|---|---|
| Single PostgreSQL instance | ~50K TPS simple queries, ~5K TPS complex joins |
| Postgres + partitioning + read replicas | ~200K read TPS, ~20K write TPS |
| Ledger transfers | ~2K TPS per instance group before hot-account contention dominates |
| Kafka break-even | >1K sustained events/sec |
| Sharding trigger | >100M `transactions` rows or >10K concurrent users |

Interpretation:
- the system can likely scale **far enough for an MVP and early growth** with partitioning + replicas + Redis HA alone;
- sharding should be treated as a late-stage investment.

---

## 11. Migration roadmap

## 11.1 Priority order

| Step | Change | Why now | Codebase impact |
|---|---|---|---|
| 1 | Read replicas + `AbstractRoutingDataSource` | Fastest improvement without schema change | new datasource config classes, read services |
| 2 | Partition `transactions`/`entries`/`payment_orders`/`payment_outbox` | Controls table growth and retention | new Flyway migrations + jOOQ regen |
| 3 | Redis Sentinel, then Redis Cluster | Remove shared cache SPOF | config + Redis client topology updates |
| 4 | Kafka integration | Better async scale and fan-out | `spring-kafka`, `KafkaPaymentEventPublisher`, consumers |
| 5 | API Gateway (Kong/AWS) | Centralize auth, rate limit, TLS, balancing | infra/deployment work |
| 6 | Citus sharding | Highest complexity, defer until required | data model and transfer orchestration changes |

## 11.2 Detailed execution plan

### Step 1 — Read replicas (today)
- Add primary/replica datasource properties to all service `application.yml` files.
- Add routing datasource config + jOOQ bean.
- Move direct controller reads to `@Transactional(readOnly = true)` query services.
- Add a sticky-primary rule for immediate post-write reads.

### Step 2 — Partition history tables
- Add new Flyway migrations in ledger and payment services.
- Create partitioned shadow tables.
- Backfill in batches.
- Swap tables in a maintenance window.
- Add `pg_partman` maintenance job.
- Add retention/archive operational runbook.

### Step 3 — Redis HA
- Move local Redis to Sentinel first.
- Convert spend-counter update to Lua.
- Convert rate limiter to Redis.
- Later migrate to Cluster when memory/throughput requires it.

### Step 4 — Kafka
- Add Kafka broker/container to local and production topology.
- Replace `LoggingPaymentEventPublisher` with `KafkaPaymentEventPublisher` behind a feature flag.
- Keep outbox table for transactional safety.
- Add consumers for notifications/reporting/ledger projections.
- Evaluate Debezium CDC to remove polling later.

### Step 5 — API gateway
- Put Kong/API Gateway in front of services.
- Move JWT validation, rate limiting, TLS termination, correlation IDs, and circuit breaking to the edge.
- Keep internal service authentication for east-west traffic.

### Step 6 — Sharding/Citus (only at scale)
- Choose `user_id` as distribution key.
- Rework transfer flows for cross-shard saga handling.
- Introduce reconciliation and compensation jobs before enabling distributed transfers.

---

## 12. Final recommendation

**Recommended scaling sequence for this NeoBank workshop:**

1. **Read replicas now** — cheapest win, no schema risk.
2. **Partition history tables next** — especially `entries` and `transactions`.
3. **Make Redis highly available** — Sentinel first, Cluster later.
4. **Replace logging-based event publishing with Kafka** — keep the outbox pattern.
5. **Introduce a real API gateway** — simplify services and improve edge resilience.
6. **Delay sharding until the metrics force it** — use `user_id` and Citus when the single-node model stops being economical.

This path keeps the MVP simple while giving the project a practical route from **single-node microservices** to a **production-grade, high-volume NeoBank architecture**.
