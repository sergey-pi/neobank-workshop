package com.neobank.ledgerservice.service;

import com.neobank.common.exception.UnprocessableException;
import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.dto.TransferResponse;
import com.neobank.ledgerservice.gateway.KycGateway;
import com.neobank.ledgerservice.jooq.tables.Accounts;
import com.neobank.ledgerservice.jooq.tables.Balances;
import com.neobank.ledgerservice.jooq.tables.Entries;
import com.neobank.ledgerservice.jooq.tables.Transactions;
import com.neobank.ledgerservice.jooq.tables.records.BalancesRecord;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Orchestrates money transfers using double-entry bookkeeping.
 *
 * <p><b>HTTP vs transaction boundary:</b> the KYC check (step 3) is an HTTP call to
 * user-service. It runs <em>before</em> the database transaction opens so that a
 * slow or failing user-service does not hold a DB lock. Steps 4–13 execute inside
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
 *   <li>Lock {@code fromBalance} with {@code SELECT FOR UPDATE}</li>
 *   <li>Fetch {@code toBalance} for currency and existence validation</li>
 *   <li>Currency validation (both accounts + request must match)</li>
 *   <li>Daily spend limit check (SUM of today's debits)</li>
 *   <li>Sufficient funds check</li>
 *   <li>Insert {@code transactions} row as {@code PENDING}</li>
 *   <li>Insert two {@code entries} rows (debit + credit)</li>
 *   <li>Debit {@code fromAccount} balance</li>
 *   <li>Credit {@code toAccount} balance</li>
 *   <li>Mark transaction {@code COMPLETED}</li>
 * </ol></p>
 */
@Service
public class LedgerService {

    private final DSLContext dsl;
    private final KycGateway kycGateway;
    private final TransactionTemplate transactionTemplate;

    @Value("${security.limits.max-transfer-amount}")
    private long maxTransferAmount;

    @Value("${security.limits.daily-spend-limit}")
    private long dailySpendLimit;

    public LedgerService(DSLContext dsl, KycGateway kycGateway, PlatformTransactionManager txManager) {
        this.dsl = dsl;
        this.kycGateway = kycGateway;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Executes a P2P transfer. Steps 1–3 run outside any transaction so the KYC
     * HTTP call does not hold a DB connection. Steps 4–13 run atomically inside
     * a {@link TransactionTemplate}.
     *
     * @param request transfer parameters (amounts in minor units)
     * @return completed transfer summary
     */
    public TransferResponse transfer(TransferRequest request) {
        // 1. Per-transaction limit check — fast fail before any I/O.
        if (request.amount() > maxTransferAmount) {
            throw new UnprocessableException(
                    "Transfer amount " + request.amount()
                    + " exceeds per-transaction limit of " + maxTransferAmount);
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

        // Steps 4–13: all DB writes are atomic inside one transaction.
        return transactionTemplate.execute(status -> executeTransfer(request));
    }

    /**
     * Inner transactional body. Runs inside the {@link TransactionTemplate} opened
     * by {@link #transfer}. Any unchecked exception causes automatic rollback.
     */
    private TransferResponse executeTransfer(TransferRequest request) {
        // 4. Lock fromAccount balance row with SELECT FOR UPDATE.
        //    Establishes consistent ordering and prevents double-spend.
        BalancesRecord fromBalance = dsl.selectFrom(Balances.BALANCES)
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.fromAccountId()))
                .forUpdate()
                .fetchOne();

        if (fromBalance == null) {
            throw new IllegalArgumentException(
                    "From account not found or has no balance: " + request.fromAccountId());
        }

        // 5. Fetch toAccount balance for currency and existence validation.
        BalancesRecord toBalance = dsl.selectFrom(Balances.BALANCES)
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.toAccountId()))
                .fetchOne();

        if (toBalance == null) {
            throw new IllegalArgumentException(
                    "Destination account not found or has no balance: " + request.toAccountId());
        }

        // 6. Currency validation — prevents cross-currency transfers.
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

        // 7. Daily spend limit check — inside the transaction so the SUM is
        //    consistent with the lock acquired in step 4.
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
            throw new UnprocessableException("Daily spend limit exceeded");
        }

        // 8. Sufficient funds check — lock held since step 4.
        if (fromBalance.getAvailableAmount() < request.amount()) {
            throw new IllegalArgumentException(
                    "Insufficient funds in account " + request.fromAccountId());
        }

        UUID transactionId = UUID.randomUUID();

        // 9. Insert Transaction as PENDING — marked COMPLETED only after all writes.
        dsl.insertInto(Transactions.TRANSACTIONS)
                .set(Transactions.TRANSACTIONS.ID, transactionId)
                .set(Transactions.TRANSACTIONS.REFERENCE, UUID.randomUUID().toString())
                .set(Transactions.TRANSACTIONS.TYPE, "P2P_TRANSFER")
                .set(Transactions.TRANSACTIONS.STATUS, "PENDING")
                .set(Transactions.TRANSACTIONS.DESCRIPTION, request.description())
                .execute();

        // 10. Insert double-entry ledger entries.
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

        // 11. Debit fromAccount — lock held since step 4.
        dsl.update(Balances.BALANCES)
                .set(Balances.BALANCES.AVAILABLE_AMOUNT,
                        Balances.BALANCES.AVAILABLE_AMOUNT.minus(request.amount()))
                .set(Balances.BALANCES.VERSION, Balances.BALANCES.VERSION.plus(1))
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.fromAccountId()))
                .execute();

        // 12. Credit toAccount. Zero rows updated means the balance row vanished.
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

        // 13. All writes succeeded — mark transaction COMPLETED.
        dsl.update(Transactions.TRANSACTIONS)
                .set(Transactions.TRANSACTIONS.STATUS, "COMPLETED")
                .where(Transactions.TRANSACTIONS.ID.eq(transactionId))
                .execute();

        return new TransferResponse(transactionId, "COMPLETED", "Transfer successful");
    }
}
