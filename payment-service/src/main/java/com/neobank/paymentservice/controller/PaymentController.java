package com.neobank.paymentservice.controller;

import com.neobank.paymentservice.dto.PaymentRequest;
import com.neobank.paymentservice.dto.PaymentResponse;
import com.neobank.paymentservice.jooq.tables.PaymentOrders;
import com.neobank.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final DSLContext dsl;
    private final PaymentService paymentService;

    public PaymentController(DSLContext dsl, PaymentService paymentService) {
        this.dsl = dsl;
        this.paymentService = paymentService;
    }

    @PostMapping
    public PaymentResponse processPayment(@Valid @RequestBody PaymentRequest request) {
        return paymentService.processPayment(request);
    }

    @GetMapping
    public List<Map<String, Object>> getPayments() {
        return dsl.selectFrom(PaymentOrders.PAYMENT_ORDERS)
                .fetchMaps();
    }
}
