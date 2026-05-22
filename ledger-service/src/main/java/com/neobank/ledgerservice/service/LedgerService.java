package com.neobank.ledgerservice.service;

import com.neobank.common.exception.UnprocessableException;
import com.neobank.ledgerservice.cache.SpendCounterCache;
import com.neobank.ledgerservice.cache.TransferIdempotencyCache;
import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.dto.TransferResponse;
import com.neobank.ledgerservice.gateway.KycGateway;
import com.neobank.ledgerservice.jooq.tables.Accounts;
import com.neobank.ledgerservice.jooq.tables.Balances;
import com.neobank.ledgerservice.jooq.tables.Entries;
import com.neobank.ledgerservice.jooq.tables.Transactions;
import com.neobank.ledgerservice.jooq.tables.records.BalancesRecord;
import com.neobank.ledgerservice.jooq.tables.records.TransactionsRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates money transfers using double-entry bookkeeping.
 *
 * <p><b>HTTP vs transaction boundary:</b> the KYC check (step 3) is an HTTP call to
 * user-service. It runs <em>before</em> the database transaction opens so that a
 * slow or failing user-service does not hold a DB lock. Steps 5–13 execute inside
 * an explicit {@link TransactionTemplate}; {@code @Transactional} on the calling
 * method would have caused self-invocation through the Spring proxy had the DB steps
 * been split into a private method — using {@code TransactionTemplate} avoids that
 * trap entirely.</p>
 *
 * <p><b>Transfer execution order:</b>
 * <ol>
 *   <li>Per-transaction limit check (fast fail, no DB)</li>
 *   <li>Resolve {@code userId} from {@code fromAccount}</li>
 *   <li>KYC gate — HTTP call (outside transaction)</li>
 *   <li>Daily spend check — Redis fast-path; SQL fallback inside tx if Redis is down</li>
 *   <li>Lock {@code fromBalance} with {@code SELECT FOR UPDATE}</li>
 *   <li>Fetch {@code toBalance} for currency and existence validation</li>
 *   <li>Currency validation (both accounts + request must match)</li>
 *   <li>Sufficient funds check</li>
 *   <li>Insert {@code transactions} row as {@code PENDING}</li>
 *   <li>Insert two {@code entries} rows (debit + credit)</li>
 *   <li>Debit {@code fromAccount} balance</li>
 *   <li>Credit {@code toAccount} balance</li>
 *   <li>Mark transaction {@code COMPLETED}</li>
 *   <li>Increment Redis spend counter (after DB commit succeeds)</li>
 * </ol></p>
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final DSLContext dsl;
    private final KycGateway kycGateway;
    private final SpendCounterCache spendCounterCache;
    private final TransferIdempotencyCache idempotencyCache;
    private final TransactionTemplate transactionTemplate;

    @Value("${security.limits.max-transfer-amount}")
    private long maxTransferAmount;

    @Value("${security.limits.daily-spend-limit}")
    private long dailySpendLimit;

    public LedgerService(DSLContext dsl, KycGateway kycGateway,
                         SpendCounterCache spendCounterCache,
                         TransferIdempotencyCache idempotencyCache,
                         PlatformTransactionManager txManager) {
        this.dsl = dsl;
        this.kycGateway = kycGateway;
        this.spendCounterCache = spendCounterCache;
        this.idempotencyCache = idempotencyCache;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Executes a P2P transfer with optional idempotency.
     *
     * <p>If {@code idempotencyKey} is provided, the flow is:
     * <ol>
     *   <li>Redis cache hit → return cached response immediately (no DB)</li>
     *   <li>Redis cache miss → proceed with transfer</li>
     *   <li>DB-level unique index on {@code idempotency_key} is the authoritative guard:
     *       concurrent requests with the same key get {@code ON CONFLICT DO NOTHING},
     *       which fetches and returns the existing transaction.</li>
     *   <li>On success, store transactionId in Redis for future fast-path hits.</li>
     * </ol></p>
     *
     * @param request        transfer parameters (amounts in minor units)
     * @param idempotencyKey optional client-supplied idempotency key (from {@code Idempotency-Key} header)
     * @return completed transfer summary
     */
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        // Redis fast-path: return cached result without any DB work.
        if (idempotencyKey != null) {
            Optional<String> cached = idempotencyCache.get(idempotencyKey);
            if (cached.isPresent()) {
                log.debug("Idempotency cache hit for key={}", idempotencyKey.replaceAll("[\r\n]", "_"));
                return new TransferResponse(UUID.fromString(cached.get()), "COMPLETED",
                        "Transfer already processed");
            }
        }
        // 1. Per-transaction limit check — fast fail before any I/O.
        if (request.amount() > maxTransferAmount) {
            log.warn("Transfer rejected: amount {} exceeds per-transaction limit {} for account {}",
                    request.amount(), maxTransferAmount, request.fromAccountId());
            throw new UnprocessableException("Transfer amount exceeds the per-transaction limit");
        }

        // 2. Resolve userId — needed to identify the KYC subject.
        UUID fromUserId = dsl.select(Accounts.ACCOUNTS.USER_ID)
                .from(Accounts.ACCOUNTS)
                .where(Accounts.ACCOUNTS.ID.eq(request.fromAccountId()))
                .fetchOne(Accounts.ACCOUNTS.USER_ID);

        if (fromUserId == null) {
            throw new IllegalArgumentException(
                    "From account not found or has no balance: " + request.fromAccountId());
        }

        // 3. KYC gate — HTTP call to user-service. Runs BEFORE the transaction
        //    opens so a slow KYC service does not hold a DB connection/lock.
        kycGateway.requireKycApproved(fromUserId);

        // 4. Daily spend early fast-fail via Redis (key: spend:{accountId}:{date}, TTL 25 h).
        //    This is an optimisation only — NOT the authoritative check.
        //    The authoritative serialised check is step 8 inside the transaction.
        //    Returns -1 when Redis is unavailable; the tx-level SQL check always runs regardless.
        long redisSpent = spendCounterCache.get(request.fromAccountId());
        if (redisSpent >= 0 && redisSpent + request.amount() > dailySpendLimit) {
            log.warn("Transfer fast-rejected by Redis counter: spent {} + amount {} > limit {} for account {}",
                    redisSpent, request.amount(), dailySpendLimit, request.fromAccountId());
            throw new UnprocessableException("daily spend limit exceeded");
        }

        // Steps 5–13: all DB writes are atomic inside one transaction.
        TransferResponse result = transactionTemplate.execute(
                status -> executeTransfer(request, idempotencyKey));

        // 14. Atomically increment the Redis daily spend counter after DB commit.
        //     TTL is 25 hours so the key expires shortly after midnight UTC.
        //     Failures are silently swallowed — the SQL fallback in step 4 covers future requests.
        spendCounterCache.incrementAndGet(request.fromAccountId(), request.amount());

        // Store in idempotency cache after successful commit so future requests get fast-path.
        if (idempotencyKey != null && result != null) {
            idempotencyCache.putIfAbsent(idempotencyKey, result.transactionId().toString());
        }

        return result;
    }

    /**
     * Inner transactional body. Runs inside the {@link TransactionTemplate} opened
     * by {@link #transfer}. Any unchecked exception causes automatic rollback.
     */
    private TransferResponse executeTransfer(TransferRequest request, String idempotencyKey) {
        // 5. Lock fromAccount balance row with SELECT FOR UPDATE.
        //    Establishes consistent ordering and prevents double-spend.
        BalancesRecord fromBalance = dsl.selectFrom(Balances.BALANCES)
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.fromAccountId()))
                .forUpdate()
                .fetchOne();

        if (fromBalance == null) {
            throw new IllegalArgumentException(
                    "From account not found or has no balance: " + request.fromAccountId());
        }

        // 6. Fetch toAccount balance for currency and existence validation.
        BalancesRecord toBalance = dsl.selectFrom(Balances.BALANCES)
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.toAccountId()))
                .fetchOne();

        if (toBalance == null) {
            throw new IllegalArgumentException(
                    "Destination account not found or has no balance: " + request.toAccountId());
        }

        // 7. Currency validation — prevents cross-currency transfers.
        if (!fromBalance.getCurrency().equals(toBalance.getCurrency())) {
            throw new IllegalArgumentException(
                    "Currency mismatch between accounts: source is "
                            + fromBalance.getCurrency() + ", destination is "
                            + toBalance.getCurrency());
        }
        if (!fromBalance.getCurrency().equals(request.currency())) {
            throw new IllegalArgumentException(
                    "Request currency " + request.currency()
                            + " does not match account currency " + fromBalance.getCurrency());
        }

        // 8. Authoritative daily spend check — ALWAYS runs inside the transaction, serialised
        //    by the SELECT FOR UPDATE lock acquired in step 5. This prevents the TOCTOU race
        //    where two concurrent transfers from the same account both pass the Redis fast-fail
        //    before either commits. After step 5's lock is released by the prior holder, this
        //    re-reads committed entries and correctly sees the updated spend total.
        OffsetDateTime startOfDay = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay().atOffset(ZoneOffset.UTC);
        Long todaySpend = dsl.select(org.jooq.impl.DSL.sum(Entries.ENTRIES.AMOUNT).neg())
                .from(Entries.ENTRIES)
                .where(Entries.ENTRIES.ACCOUNT_ID.eq(request.fromAccountId()))
                .and(Entries.ENTRIES.TYPE.eq("DEBIT"))
                .and(Entries.ENTRIES.CREATED_AT.greaterOrEqual(startOfDay))
                .fetchOne(0, Long.class);

        long spent = todaySpend == null ? 0L : todaySpend;
        if (spent + request.amount() > dailySpendLimit) {
            log.warn("Transfer rejected by authoritative SQL check: spent {} + amount {} > limit {} for account {}",
                    spent, request.amount(), dailySpendLimit, request.fromAccountId());
            throw new UnprocessableException("daily spend limit exceeded");
        }

        // 9. Sufficient funds check — lock held since step 5.
        if (fromBalance.getAvailableAmount() < request.amount()) {
            throw new IllegalArgumentException(
                    "Insufficient funds in account " + request.fromAccountId());
        }

        UUID transactionId = UUID.randomUUID();

        // 10. Insert Transaction as PENDING with optional idempotency key.
        //     ON CONFLICT (idempotency_key) DO NOTHING is the authoritative DB-level guard:
        //     if two concurrent requests arrive with the same key, only one inserts; the other
        //     gets 0 rows inserted and we return the existing transaction instead.
        int inserted = dsl.insertInto(Transactions.TRANSACTIONS)
                .set(Transactions.TRANSACTIONS.ID, transactionId)
                .set(Transactions.TRANSACTIONS.REFERENCE, UUID.randomUUID().toString())
                .set(Transactions.TRANSACTIONS.TYPE, "P2P_TRANSFER")
                .set(Transactions.TRANSACTIONS.STATUS, "PENDING")
                .set(Transactions.TRANSACTIONS.DESCRIPTION, request.description())
                .set(Transactions.TRANSACTIONS.IDEMPOTENCY_KEY, idempotencyKey)
                .onConflict(Transactions.TRANSACTIONS.IDEMPOTENCY_KEY)
                .doNothing()
                .execute();

        if (inserted == 0) {
            // Duplicate idempotency key — return the already-committed transaction.
            TransactionsRecord existing = dsl.selectFrom(Transactions.TRANSACTIONS)
                    .where(Transactions.TRANSACTIONS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                    .fetchOne();
            if (existing != null) {
                return new TransferResponse(existing.getId(), existing.getStatus(),
                        "Transfer already processed");
            }
        }

        // 11. Insert double-entry ledger entries.
        dsl.insertInto(Entries.ENTRIES)
                .set(Entries.ENTRIES.ID, UUID.randomUUID())
                .set(Entries.ENTRIES.TRANSACTION_ID, transactionId)
                .set(Entries.ENTRIES.ACCOUNT_ID, request.fromAccountId())
                .set(Entries.ENTRIES.AMOUNT, -request.amount())
                .set(Entries.ENTRIES.TYPE, "DEBIT")
                .set(Entries.ENTRIES.CURRENCY, request.currency())
                .set(Entries.ENTRIES.DESCRIPTION, "Debit for transfer to " + request.toAccountId())
                .execute();

        dsl.insertInto(Entries.ENTRIES)
                .set(Entries.ENTRIES.ID, UUID.randomUUID())
                .set(Entries.ENTRIES.TRANSACTION_ID, transactionId)
                .set(Entries.ENTRIES.ACCOUNT_ID, request.toAccountId())
                .set(Entries.ENTRIES.AMOUNT, request.amount())
                .set(Entries.ENTRIES.TYPE, "CREDIT")
                .set(Entries.ENTRIES.CURRENCY, request.currency())
                .set(Entries.ENTRIES.DESCRIPTION, "Credit from transfer from " + request.fromAccountId())
                .execute();

        // 12. Debit fromAccount — lock held since step 5.
        dsl.update(Balances.BALANCES)
                .set(Balances.BALANCES.AVAILABLE_AMOUNT,
                        Balances.BALANCES.AVAILABLE_AMOUNT.minus(request.amount()))
                .set(Balances.BALANCES.VERSION, Balances.BALANCES.VERSION.plus(1))
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.fromAccountId()))
                .execute();

        // 13. Credit toAccount. Zero rows updated means the balance row vanished.
        int updatedTo = dsl.update(Balances.BALANCES)
                .set(Balances.BALANCES.AVAILABLE_AMOUNT,
                        Balances.BALANCES.AVAILABLE_AMOUNT.plus(request.amount()))
                .set(Balances.BALANCES.VERSION, Balances.BALANCES.VERSION.plus(1))
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.toAccountId()))
                .execute();

        if (updatedTo == 0) {
            throw new IllegalStateException(
                    "Destination account has no balance row: " + request.toAccountId());
        }

        // Mark transaction COMPLETED — all writes succeeded.
        dsl.update(Transactions.TRANSACTIONS)
                .set(Transactions.TRANSACTIONS.STATUS, "COMPLETED")
                .where(Transactions.TRANSACTIONS.ID.eq(transactionId))
                .execute();

        return new TransferResponse(transactionId, "COMPLETED", "Transfer successful");
    }
}
