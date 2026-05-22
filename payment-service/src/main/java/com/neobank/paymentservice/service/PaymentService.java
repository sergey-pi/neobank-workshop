package com.neobank.paymentservice.service;

import com.neobank.paymentservice.cache.IdempotencyCache;
import com.neobank.paymentservice.dto.PaymentRequest;
import com.neobank.paymentservice.dto.PaymentResponse;
import com.neobank.paymentservice.jooq.tables.PaymentOrders;
import com.neobank.paymentservice.jooq.tables.PaymentOutbox;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Processes payment orders using the Transactional Outbox pattern.
 *
 * <p>Each call atomically writes two records in the same DB transaction:
 * <ol>
 *   <li>{@code payment_orders} — the authoritative payment record</li>
 *   <li>{@code payment_outbox} — event stub picked up by {@link OutboxPoller}</li>
 * </ol>
 *
 * <p>Idempotency: if the caller supplies an {@code idempotencyKey}, the service checks
 * Redis ({@code idem:{key}}) before touching the DB. A duplicate key returns the
 * previously stored {@code orderId} immediately. The Redis entry uses SET NX with a
 * 24-hour TTL, so keys expire naturally without manual cleanup.
 *
 * <p>If Redis is unavailable the idempotency check is skipped and processing continues
 * normally — this is the safe "fail-open" choice for the MVP.
 */
@Service
public class PaymentService {

    private final DSLContext dsl;
    private final IdempotencyCache idempotencyCache;

    public PaymentService(DSLContext dsl, IdempotencyCache idempotencyCache) {
        this.dsl = dsl;
        this.idempotencyCache = idempotencyCache;
    }

    /**
     * Submits a new payment order.
     *
     * <p>If {@code request.idempotencyKey()} is non-null and a cached entry exists,
     * returns the cached response without any DB writes (short-circuit path).
     *
     * @param request payment details including optional idempotency key
     * @return settled payment response with the order UUID
     */
    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        // Idempotency fast-path: if this key was already processed, return the
        // cached orderId immediately — no DB writes, no duplicate outbox event.
        if (request.idempotencyKey() != null) {
            return idempotencyCache.get(request.idempotencyKey())
                    .map(cachedOrderId -> new PaymentResponse(
                            UUID.fromString(cachedOrderId), "SETTLED", "Payment already processed (idempotent)"))
                    .orElseGet(() -> createAndCachePayment(request));
        }
        return createAndCachePayment(request);
    }

    /**
     * Performs the actual DB writes and caches the result for idempotency.
     * Called only when no cached entry exists for the given idempotency key.
     */
    private PaymentResponse createAndCachePayment(PaymentRequest request) {
        UUID orderId = UUID.randomUUID();
        // Use the caller-supplied idempotency key if present; generate one otherwise.
        // The key is stored in payment_orders for audit and deduplication at the DB level.
        String idempotencyKey = request.idempotencyKey() != null
                ? request.idempotencyKey()
                : UUID.randomUUID().toString();

        // 1. Create the payment order in INITIATED state. Status is updated to
        //    SETTLED immediately in the MVP (no async settlement step).
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

        // 2. Write the outbox event in the SAME transaction as the order insert.
        //    This guarantees the event is never lost even if the poller crashes before
        //    reading it — the DB is the single source of truth.
        dsl.insertInto(PaymentOutbox.PAYMENT_OUTBOX)
                .set(PaymentOutbox.PAYMENT_OUTBOX.ID, UUID.randomUUID())
                .set(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_TYPE, "PaymentOrder")
                .set(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_ID, orderId)
                .set(PaymentOutbox.PAYMENT_OUTBOX.TYPE, "PAYMENT_CREATED")
                .set(PaymentOutbox.PAYMENT_OUTBOX.PAYLOAD, JSONB.valueOf("{\"orderId\":\"" + orderId + "\"}"))
                .set(PaymentOutbox.PAYMENT_OUTBOX.STATUS, "PENDING")
                .execute();

        // 3. Immediately settle the order for MVP simplicity.
        //    A production system would leave this as INITIATED until the outbox event
        //    is processed and the downstream ledger confirms the debit.
        dsl.update(PaymentOrders.PAYMENT_ORDERS)
                .set(PaymentOrders.PAYMENT_ORDERS.STATUS, "SETTLED")
                .where(PaymentOrders.PAYMENT_ORDERS.ID.eq(orderId))
                .execute();

        // 4. Store the orderId in Redis so duplicate submissions return immediately.
        //    Uses SET NX — if Redis already has a racing insert we don't overwrite it.
        if (request.idempotencyKey() != null) {
            idempotencyCache.putIfAbsent(request.idempotencyKey(), orderId.toString());
        }

        return new PaymentResponse(orderId, "SETTLED", "Payment settled successfully");
    }
}
