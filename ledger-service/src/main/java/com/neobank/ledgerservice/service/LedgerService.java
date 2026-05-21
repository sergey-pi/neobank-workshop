package com.neobank.ledgerservice.service;

import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.dto.TransferResponse;
import com.neobank.ledgerservice.jooq.tables.Balances;
import com.neobank.ledgerservice.jooq.tables.Entries;
import com.neobank.ledgerservice.jooq.tables.Transactions;
import com.neobank.ledgerservice.jooq.tables.records.BalancesRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class LedgerService {

    private final DSLContext dsl;

    public LedgerService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        // 1. Lock fromAccount balance row with SELECT FOR UPDATE.
        //    This must happen first — before any inserts — to establish a
        //    consistent ordering and prevent double-spend under concurrency.
        BalancesRecord fromBalance = dsl.selectFrom(Balances.BALANCES)
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.fromAccountId()))
                .forUpdate()
                .fetchOne();

        if (fromBalance == null) {
            throw new IllegalArgumentException(
                    "From account not found or has no balance: " + request.fromAccountId());
        }

        // 2. Fetch toAccount balance for currency and existence validation.
        //    Using balances table (not accounts) because we need the currency
        //    value stored there, and a balance row is required for credit anyway.
        BalancesRecord toBalance = dsl.selectFrom(Balances.BALANCES)
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.toAccountId()))
                .fetchOne();

        if (toBalance == null) {
            throw new IllegalArgumentException(
                    "Destination account not found or has no balance: " + request.toAccountId());
        }

        // 3. Validate currencies. Both accounts must share the same currency,
        //    and the request currency must match — prevents cross-currency transfers
        //    that would require an FX rate (not supported in this service).
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

        // 4. Validate funds now that we hold the lock.
        if (fromBalance.getAvailableAmount() < request.amount()) {
            throw new IllegalArgumentException(
                    "Insufficient funds in account " + request.fromAccountId());
        }

        UUID transactionId = UUID.randomUUID();

        // 5. Insert Transaction as PENDING — mark COMPLETED only after all
        //    balance updates succeed.
        dsl.insertInto(Transactions.TRANSACTIONS)
                .set(Transactions.TRANSACTIONS.ID, transactionId)
                .set(Transactions.TRANSACTIONS.REFERENCE, UUID.randomUUID().toString())
                .set(Transactions.TRANSACTIONS.TYPE, "P2P_TRANSFER")
                .set(Transactions.TRANSACTIONS.STATUS, "PENDING")
                .set(Transactions.TRANSACTIONS.DESCRIPTION, request.description())
                .execute();

        // 6. Insert double-entry ledger entries.
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

        // 7. Debit fromAccount — we already hold the lock and validated funds.
        dsl.update(Balances.BALANCES)
                .set(Balances.BALANCES.AVAILABLE_AMOUNT,
                        Balances.BALANCES.AVAILABLE_AMOUNT.minus(request.amount()))
                .set(Balances.BALANCES.VERSION, Balances.BALANCES.VERSION.plus(1))
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.fromAccountId()))
                .execute();

        // 8. Credit toAccount. Safety guard — toBalance was verified in step 2,
        //    but returning 0 here would mean money disappeared silently.
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

        // 9. All writes succeeded — mark transaction COMPLETED.
        dsl.update(Transactions.TRANSACTIONS)
                .set(Transactions.TRANSACTIONS.STATUS, "COMPLETED")
                .where(Transactions.TRANSACTIONS.ID.eq(transactionId))
                .execute();

        return new TransferResponse(transactionId, "COMPLETED", "Transfer successful");
    }
}
