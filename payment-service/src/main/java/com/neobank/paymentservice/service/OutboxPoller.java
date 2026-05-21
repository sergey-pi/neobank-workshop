package com.neobank.paymentservice.service;

import com.neobank.paymentservice.jooq.tables.PaymentOutbox;
import com.neobank.paymentservice.jooq.tables.records.PaymentOutboxRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    private final DSLContext dsl;
    private final int maxRetries;

    public OutboxPoller(
            DSLContext dsl,
            @Value("${outbox.poll.max-retries:3}") int maxRetries) {
        this.dsl = dsl;
        this.maxRetries = maxRetries;
    }

    // Poll interval driven by fixedDelayString so it is fully configurable
    // without recompilation. Defaults to 5 seconds.
    @Scheduled(fixedDelayString = "${outbox.poll.interval-ms:5000}")
    public void poll() {
        List<PaymentOutboxRecord> events = fetchPendingBatch();
        if (events.isEmpty()) {
            return;
        }
        log.debug("Outbox poller picked up {} event(s)", events.size());
        for (PaymentOutboxRecord event : events) {
            processSingle(event.getId().toString(), event);
        }
    }

    // Package-private for testing — fetches up to 10 PENDING events using
    // SELECT FOR UPDATE SKIP LOCKED so concurrent poller instances never
    // process the same row.
    List<PaymentOutboxRecord> fetchPendingBatch() {
        return dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                .where(PaymentOutbox.PAYMENT_OUTBOX.STATUS.eq(STATUS_PENDING))
                .orderBy(PaymentOutbox.PAYMENT_OUTBOX.CREATED_AT.asc())
                .limit(10)
                .forUpdate()
                .skipLocked()
                .fetchInto(PaymentOutboxRecord.class);
    }

    @Transactional
    public void processSingle(String eventId, PaymentOutboxRecord event) {
        try {
            // TODO: replace with real event dispatch (Kafka, HTTP callback, etc.)
            log.info("Processing outbox event id={} type={} aggregateId={}",
                    event.getId(), event.getType(), event.getAggregateId());

            dsl.update(PaymentOutbox.PAYMENT_OUTBOX)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.STATUS, STATUS_PROCESSED)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.PROCESSED_AT, OffsetDateTime.now())
                    .where(PaymentOutbox.PAYMENT_OUTBOX.ID.eq(event.getId()))
                    .execute();

        } catch (Exception ex) {
            int nextRetry = event.getRetryCount() + 1;
            String newStatus = nextRetry >= maxRetries ? STATUS_FAILED : STATUS_PENDING;

            log.warn("Outbox event id={} processing failed (attempt {}/{}): {}",
                    eventId, nextRetry, maxRetries, ex.getMessage());

            dsl.update(PaymentOutbox.PAYMENT_OUTBOX)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.RETRY_COUNT, nextRetry)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.ERROR_MESSAGE, ex.getMessage())
                    .set(PaymentOutbox.PAYMENT_OUTBOX.STATUS, newStatus)
                    .where(PaymentOutbox.PAYMENT_OUTBOX.ID.eq(event.getId()))
                    .execute();
        }
    }
}
