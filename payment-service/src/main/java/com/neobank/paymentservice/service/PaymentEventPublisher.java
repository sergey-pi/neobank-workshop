package com.neobank.paymentservice.service;

import com.neobank.paymentservice.jooq.tables.records.PaymentOutboxRecord;

/**
 * Publishes a dispatched payment outbox event to downstream systems.
 *
 * <p>The default implementation ({@link LoggingPaymentEventPublisher}) logs the event.
 * Wire a Kafka or HTTP implementation in production to replace it.</p>
 */
public interface PaymentEventPublisher {

    /**
     * Publishes the given outbox record.
     *
     * <p>Called inside a transaction — if this method throws, the transaction
     * will roll back and the outbox row remains PENDING for retry.</p>
     *
     * @param record the outbox row to dispatch
     */
    void publish(PaymentOutboxRecord record);
}
