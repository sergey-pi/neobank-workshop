package com.neobank.ledgerservice.controller;

import com.neobank.common.security.JwtPrincipal;
import com.neobank.ledgerservice.dto.PagedResponse;
import com.neobank.ledgerservice.dto.TransactionResponse;
import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.dto.TransferResponse;
import com.neobank.ledgerservice.jooq.tables.Accounts;
import com.neobank.ledgerservice.jooq.tables.Entries;
import com.neobank.ledgerservice.jooq.tables.Transactions;
import com.neobank.ledgerservice.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jooq.DSLContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final DSLContext dsl;
    private final LedgerService ledgerService;

    public TransactionController(DSLContext dsl, LedgerService ledgerService) {
        this.dsl = dsl;
        this.ledgerService = ledgerService;
    }

    @PostMapping("/transfer")
    public TransferResponse transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ledgerService.transfer(request, idempotencyKey, principal.userId());
    }

    @GetMapping
    public PagedResponse<TransactionResponse> getTransactions(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Integer totalCount = dsl.selectCount()
                .from(Transactions.TRANSACTIONS)
                .where(Transactions.TRANSACTIONS.ID.in(
                        dsl.selectDistinct(Entries.ENTRIES.TRANSACTION_ID)
                                .from(Entries.ENTRIES)
                                .join(Accounts.ACCOUNTS)
                                .on(Accounts.ACCOUNTS.ID.eq(Entries.ENTRIES.ACCOUNT_ID))
                                .where(Accounts.ACCOUNTS.USER_ID.eq(principal.userId()))))
                .fetchOne(0, Integer.class);
        long total = totalCount != null ? totalCount.longValue() : 0L;

        List<TransactionResponse> items = dsl.selectDistinct(
                        Transactions.TRANSACTIONS.ID,
                        Transactions.TRANSACTIONS.REFERENCE,
                        Transactions.TRANSACTIONS.TYPE,
                        Transactions.TRANSACTIONS.STATUS,
                        Transactions.TRANSACTIONS.DESCRIPTION,
                        Transactions.TRANSACTIONS.CREATED_AT)
                .from(Transactions.TRANSACTIONS)
                .join(Entries.ENTRIES).on(Entries.ENTRIES.TRANSACTION_ID.eq(Transactions.TRANSACTIONS.ID))
                .join(Accounts.ACCOUNTS).on(Accounts.ACCOUNTS.ID.eq(Entries.ENTRIES.ACCOUNT_ID))
                .where(Accounts.ACCOUNTS.USER_ID.eq(principal.userId()))
                .orderBy(Transactions.TRANSACTIONS.CREATED_AT.desc())
                .limit(size)
                .offset((long) page * size)
                .fetch(r -> new TransactionResponse(
                        r.get(Transactions.TRANSACTIONS.ID),
                        r.get(Transactions.TRANSACTIONS.REFERENCE),
                        r.get(Transactions.TRANSACTIONS.TYPE),
                        r.get(Transactions.TRANSACTIONS.STATUS),
                        r.get(Transactions.TRANSACTIONS.DESCRIPTION),
                        r.get(Transactions.TRANSACTIONS.CREATED_AT)));

        return PagedResponse.of(items, page, size, total);
    }
}
