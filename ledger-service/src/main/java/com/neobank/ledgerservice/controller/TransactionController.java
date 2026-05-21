package com.neobank.ledgerservice.controller;

import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.dto.TransferResponse;
import com.neobank.ledgerservice.jooq.tables.Transactions;
import com.neobank.ledgerservice.service.LedgerService;
import jakarta.validation.Valid;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final DSLContext dsl;
    private final LedgerService ledgerService;

    public TransactionController(DSLContext dsl, LedgerService ledgerService) {
        this.dsl = dsl;
        this.ledgerService = ledgerService;
    }

    @PostMapping("/transfer")
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        return ledgerService.transfer(request);
    }

    @GetMapping
    public List<Map<String, Object>> getTransactions() {
        return dsl.selectFrom(Transactions.TRANSACTIONS)
                .fetchMaps();
    }
}
