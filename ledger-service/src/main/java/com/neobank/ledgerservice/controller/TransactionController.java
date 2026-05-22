package com.neobank.ledgerservice.controller;

import com.neobank.common.exception.UnauthorizedException;
import com.neobank.ledgerservice.dto.PagedResponse;
import com.neobank.ledgerservice.dto.TransactionResponse;
import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.dto.TransferResponse;
import com.neobank.ledgerservice.jooq.tables.Accounts;
import com.neobank.ledgerservice.jooq.tables.Entries;
import com.neobank.ledgerservice.jooq.tables.Transactions;
import com.neobank.ledgerservice.service.LedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jooq.DSLContext;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ledgerService.transfer(request, idempotencyKey);
    }

    @GetMapping
    public PagedResponse<TransactionResponse> getTransactions(
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        UUID userId = requireUserId(httpRequest);

        Long totalCount = dsl.selectCount()
                .from(Transactions.TRANSACTIONS)
                .join(Entries.ENTRIES).on(Entries.ENTRIES.TRANSACTION_ID.eq(Transactions.TRANSACTIONS.ID))
                .join(Accounts.ACCOUNTS).on(Accounts.ACCOUNTS.ID.eq(Entries.ENTRIES.ACCOUNT_ID))
                .where(Accounts.ACCOUNTS.USER_ID.eq(userId))
                .fetchOne(0, Long.class);
        long total = totalCount != null ? totalCount : 0L;

        List<TransactionResponse> items = dsl
                .select(
                        Transactions.TRANSACTIONS.ID,
                        Transactions.TRANSACTIONS.REFERENCE,
                        Transactions.TRANSACTIONS.TYPE,
                        Transactions.TRANSACTIONS.STATUS,
                        Transactions.TRANSACTIONS.DESCRIPTION,
                        Transactions.TRANSACTIONS.CREATED_AT,
                        Entries.ENTRIES.AMOUNT,
                        Accounts.ACCOUNTS.CURRENCY)
                .from(Transactions.TRANSACTIONS)
                .join(Entries.ENTRIES).on(Entries.ENTRIES.TRANSACTION_ID.eq(Transactions.TRANSACTIONS.ID))
                .join(Accounts.ACCOUNTS).on(Accounts.ACCOUNTS.ID.eq(Entries.ENTRIES.ACCOUNT_ID))
                .where(Accounts.ACCOUNTS.USER_ID.eq(userId))
                .orderBy(Transactions.TRANSACTIONS.CREATED_AT.desc())
                .limit(size)
                .offset((long) page * size)
                .fetch(r -> {
                    Long entryAmount = r.get(Entries.ENTRIES.AMOUNT);
                    long amount = entryAmount != null ? Math.abs(entryAmount) : 0L;
                    String direction = entryAmount != null && entryAmount < 0 ? "DEBIT" : "CREDIT";
                    return new TransactionResponse(
                            r.get(Transactions.TRANSACTIONS.ID),
                            r.get(Transactions.TRANSACTIONS.REFERENCE),
                            r.get(Transactions.TRANSACTIONS.TYPE),
                            r.get(Transactions.TRANSACTIONS.STATUS),
                            r.get(Transactions.TRANSACTIONS.DESCRIPTION),
                            r.get(Transactions.TRANSACTIONS.CREATED_AT),
                            amount,
                            r.get(Accounts.ACCOUNTS.CURRENCY),
                            direction);
                });

        return PagedResponse.of(items, page, size, total);
    }

    private UUID requireUserId(HttpServletRequest httpRequest) {
        Object userId = httpRequest.getAttribute("userId");
        if (userId instanceof UUID uuid) {
            return uuid;
        }
        throw new UnauthorizedException("Authentication required");
    }
}
