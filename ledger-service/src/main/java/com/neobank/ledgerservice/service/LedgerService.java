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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class LedgerService {

    private final DSLContext dsl;
    private final KycGateway kycGateway;

    @Value("${security.limits.max-transfer-amount}")
    private long maxTransferAmount;

    @Value("${security.limits.daily-spend-limit}")
    private long dailySpendLimit;

    public LedgerService(DSLContext dsl, KycGateway kycGateway) {
        this.dsl = dsl;
        this.kycGateway = kycGateway;
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        // 1. Per-transaction limit check — fast fail before any DB I/O.
        if (request.amount() > maxTransferAmount) {
            throw new UnprocessableException(
                    "Transfer amount " + request.amount()
                    + " exceeds per-transaction limit of " + maxTransferAmount);
        }

        // 2. Resolve userId for KYC gate.
        UUID fromUserId = dsl.select(Accounts.ACCOUNTS.USER_ID)
                .from(Accounts.ACCOUNTS)
                .where(Accounts.ACCOUNTS.ID.eq(request.fromAccountId()))
                .fetchOne(Accounts.ACCOUNTS.USER_ID);

        if (fromUserId == null) {
            throw new IllegalArgumentException(
                    "From account not found or has no balance: " + request.fromAccountId());
        }

        // 3. KYC gate — transfer is blocked if user is not APPROVED.
        kycGateway.requireKycApproved(fromUserId);

        // 4. Lock fromAccount balance row with SELECT FOR UPDATE.
        //    Must happen before any inserts to establish consistent ordering
        //    and prevent double-spend under concurrency.
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

        // 6. Validate currencies — prevents cross-currency transfers.
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

        // 7. Daily spend limit check (sum of today's debits on fromAccount).
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

        // 8. Validate sufficient funds — we hold the lock from step 4.
        if (fromBalance.getAvailableAmount() < request.amount()) {
            throw new IllegalArgumentException(
                    "Insufficient funds in account " + request.fromAccountId());
        }

        UUID transactionId = UUID.randomUUID();

        // 9. Insert Transaction as PENDING — mark COMPLETED only after all writes succeed.
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

        // 12. Credit toAccount. Return 0 means balance row disappeared — fail the transaction.
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
