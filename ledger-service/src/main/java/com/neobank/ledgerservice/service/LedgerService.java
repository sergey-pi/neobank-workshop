package com.neobank.ledgerservice.service;

import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.dto.TransferResponse;
import com.neobank.ledgerservice.jooq.tables.Accounts;
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

        // 2. Verify toAccount exists before writing anything.
        boolean toAccountExists = dsl.fetchExists(
                dsl.selectOne()
                        .from(Accounts.ACCOUNTS)
                        .where(Accounts.ACCOUNTS.ID.eq(request.toAccountId()))
                        .and(Accounts.ACCOUNTS.STATUS.eq("ACTIVE")));

        if (!toAccountExists) {
            throw new IllegalArgumentException(
                    "Destination account not found or inactive: " + request.toAccountId());
        }

        // 3. Validate funds now that we hold the lock.
        if (fromBalance.getAvailableAmount() < request.amount()) {
            throw new IllegalArgumentException(
                    "Insufficient funds in account " + request.fromAccountId());
        }

        UUID transactionId = UUID.randomUUID();

        // 4. Insert Transaction as PENDING — mark COMPLETED only after all
        //    balance updates succeed.
        dsl.insertInto(Transactions.TRANSACTIONS)
                .set(Transactions.TRANSACTIONS.ID, transactionId)
                .set(Transactions.TRANSACTIONS.REFERENCE, UUID.randomUUID().toString())
                .set(Transactions.TRANSACTIONS.TYPE, "P2P_TRANSFER")
                .set(Transactions.TRANSACTIONS.STATUS, "PENDING")
                .set(Transactions.TRANSACTIONS.DESCRIPTION, request.description())
                .execute();

        // 5. Insert double-entry ledger entries.
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

        // 6. Debit fromAccount — we already hold the lock and validated funds.
        dsl.update(Balances.BALANCES)
                .set(Balances.BALANCES.AVAILABLE_AMOUNT,
                        Balances.BALANCES.AVAILABLE_AMOUNT.minus(request.amount()))
                .set(Balances.BALANCES.VERSION, Balances.BALANCES.VERSION.plus(1))
                .where(Balances.BALANCES.ACCOUNT_ID.eq(request.fromAccountId()))
                .execute();

        // 7. Credit toAccount. If toAccount has no balance row the update
        //    returns 0 rows — money would disappear silently, so we guard it.
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

        // 8. All writes succeeded — mark transaction COMPLETED.
        dsl.update(Transactions.TRANSACTIONS)
                .set(Transactions.TRANSACTIONS.STATUS, "COMPLETED")
                .where(Transactions.TRANSACTIONS.ID.eq(transactionId))
                .execute();

        return new TransferResponse(transactionId, "COMPLETED", "Transfer successful");
    }
}
