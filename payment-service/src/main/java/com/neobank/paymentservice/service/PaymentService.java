package com.neobank.paymentservice.service;

import com.neobank.paymentservice.dto.PaymentRequest;
import com.neobank.paymentservice.dto.PaymentResponse;
import com.neobank.paymentservice.jooq.tables.PaymentOrders;
import com.neobank.paymentservice.jooq.tables.PaymentOutbox;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentService {

    private final DSLContext dsl;

    public PaymentService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        UUID orderId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString(); // Simplified for MVP

        // 1. Create Payment Order record
        // Schema columns: id, user_id, idempotency_key, type, status, amount, currency,
        // destination_details
        dsl.insertInto(PaymentOrders.PAYMENT_ORDERS)
                .set(PaymentOrders.PAYMENT_ORDERS.ID, orderId)
                .set(PaymentOrders.PAYMENT_ORDERS.USER_ID, request.senderId())
                .set(PaymentOrders.PAYMENT_ORDERS.IDEMPOTENCY_KEY, idempotencyKey)
                .set(PaymentOrders.PAYMENT_ORDERS.TYPE, "INTERNAL_P2P")
                .set(PaymentOrders.PAYMENT_ORDERS.STATUS, "INITIATED")
                .set(PaymentOrders.PAYMENT_ORDERS.AMOUNT, request.amount())
                .set(PaymentOrders.PAYMENT_ORDERS.CURRENCY, request.currency())
                .set(PaymentOrders.PAYMENT_ORDERS.DESTINATION_DETAILS,
                        JSONB.valueOf("{\"receiverId\":\"" + request.receiverId() + "\"}"))
                .execute();

        // 2. Create Outbox entry
        // Schema columns: id, aggregate_type, aggregate_id, type, payload, status
        dsl.insertInto(PaymentOutbox.PAYMENT_OUTBOX)
                .set(PaymentOutbox.PAYMENT_OUTBOX.ID, UUID.randomUUID())
                .set(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_TYPE, "PaymentOrder")
                .set(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_ID, orderId)
                .set(PaymentOutbox.PAYMENT_OUTBOX.TYPE, "PAYMENT_CREATED")
                .set(PaymentOutbox.PAYMENT_OUTBOX.PAYLOAD, JSONB.valueOf("{\"orderId\":\"" + orderId + "\"}"))
                .set(PaymentOutbox.PAYMENT_OUTBOX.STATUS, "PENDING")
                .execute();

        // 3. Update status to SETTLED (Simplified for MVP)
        dsl.update(PaymentOrders.PAYMENT_ORDERS)
                .set(PaymentOrders.PAYMENT_ORDERS.STATUS, "SETTLED")
                .where(PaymentOrders.PAYMENT_ORDERS.ID.eq(orderId))
                .execute();

        return new PaymentResponse(orderId, "SETTLED", "Payment settled successfully");
    }
}
