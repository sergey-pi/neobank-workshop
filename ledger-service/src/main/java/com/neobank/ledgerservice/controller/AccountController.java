package com.neobank.ledgerservice.controller;

import com.neobank.common.exception.ForbiddenException;
import com.neobank.common.security.AuthenticatedPrincipal;
import com.neobank.common.security.JwtPrincipal;
import com.neobank.ledgerservice.dto.AccountResponse;
import com.neobank.ledgerservice.dto.CreateAccountRequest;
import com.neobank.ledgerservice.jooq.tables.Accounts;
import com.neobank.ledgerservice.jooq.tables.Balances;
import com.neobank.ledgerservice.service.AccountService;
import jakarta.validation.Valid;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final DSLContext dsl;
    private final AccountService accountService;

    public AccountController(DSLContext dsl, AccountService accountService) {
        this.dsl = dsl;
        this.accountService = accountService;
    }

    @PostMapping
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request,
                                         @AuthenticatedPrincipal JwtPrincipal principal) {
        if (!principal.userId().equals(request.userId())) {
            throw new ForbiddenException("Cannot create accounts for other users");
        }
        return accountService.createAccount(request);
    }

    @GetMapping
    public List<AccountResponse> getAccounts(@AuthenticatedPrincipal JwtPrincipal principal) {
        return dsl.select(
                        Accounts.ACCOUNTS.ID,
                        Accounts.ACCOUNTS.USER_ID,
                        Accounts.ACCOUNTS.CURRENCY,
                        Accounts.ACCOUNTS.NAME,
                        Accounts.ACCOUNTS.TYPE,
                        Accounts.ACCOUNTS.STATUS,
                        Balances.BALANCES.AVAILABLE_AMOUNT)
                .from(Accounts.ACCOUNTS)
                .leftJoin(Balances.BALANCES).on(Balances.BALANCES.ACCOUNT_ID.eq(Accounts.ACCOUNTS.ID))
                .where(Accounts.ACCOUNTS.USER_ID.eq(principal.userId()))
                .fetch(r -> new AccountResponse(
                        r.get(Accounts.ACCOUNTS.ID),
                        r.get(Accounts.ACCOUNTS.USER_ID),
                        r.get(Accounts.ACCOUNTS.CURRENCY),
                        r.get(Accounts.ACCOUNTS.NAME),
                        r.get(Accounts.ACCOUNTS.TYPE),
                        r.get(Accounts.ACCOUNTS.STATUS),
                        r.get(Balances.BALANCES.AVAILABLE_AMOUNT) != null
                                ? r.get(Balances.BALANCES.AVAILABLE_AMOUNT) : 0L));
    }
}
