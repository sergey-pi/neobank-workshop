package com.neobank.ledgerservice.service;

import com.neobank.ledgerservice.dto.AccountResponse;
import com.neobank.ledgerservice.dto.CreateAccountRequest;
import com.neobank.ledgerservice.jooq.tables.Accounts;
import com.neobank.ledgerservice.jooq.tables.Balances;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final DSLContext dsl;

    public AccountService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        UUID accountId = UUID.randomUUID();

        // 1. Create Account record
        dsl.insertInto(Accounts.ACCOUNTS)
                .set(Accounts.ACCOUNTS.ID, accountId)
                .set(Accounts.ACCOUNTS.USER_ID, request.userId())
                .set(Accounts.ACCOUNTS.CURRENCY, request.currency())
                .set(Accounts.ACCOUNTS.NAME, request.name())
                .set(Accounts.ACCOUNTS.TYPE, request.type())
                .set(Accounts.ACCOUNTS.STATUS, "ACTIVE")
                .execute();

        // 2. Create Balance record
        dsl.insertInto(Balances.BALANCES)
                .set(Balances.BALANCES.ACCOUNT_ID, accountId)
                .set(Balances.BALANCES.CURRENCY, request.currency())
                .set(Balances.BALANCES.AVAILABLE_AMOUNT, 0L)
                .set(Balances.BALANCES.PENDING_AMOUNT, 0L)
                .set(Balances.BALANCES.VERSION, 1L)
                .execute();

        return new AccountResponse(
                accountId,
                request.userId(),
                request.currency(),
                request.name(),
                request.type(),
                "ACTIVE",
                0L);
    }
}
