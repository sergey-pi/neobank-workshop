package com.neobank.ledgerservice.controller;

import com.neobank.ledgerservice.dto.AccountResponse;
import com.neobank.ledgerservice.dto.CreateAccountRequest;
import com.neobank.ledgerservice.jooq.tables.Accounts;
import com.neobank.ledgerservice.service.AccountService;
import jakarta.validation.Valid;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    @GetMapping
    public List<Map<String, Object>> getAccounts() {
        return dsl.selectFrom(Accounts.ACCOUNTS)
                .fetchMaps();
    }
}
