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

/**
 * Polls {@code payment_outbox} for PENDING events and dispatches them.
 *
 * <p><b>Concurrency safety:</b> {@code SELECT FOR UPDATE SKIP LOCKED} ensures
 * that multiple running instances never process the same row simultaneously.</p>
 *
 * <p><b>Exponential back-off:</b> on failure, {@code next_retry_at} is set to
 * {@code now + retryBaseDelaySeconds * 2^(retryCount - 1)}, preventing a
 * hot-retry loop against a persistently failing downstream system:
 * <pre>
 *   attempt 1 → wait retryBaseDelaySeconds     (e.g. 30 s)
 *   attempt 2 → wait retryBaseDelaySeconds * 2  (e.g. 60 s)
 *   attempt 3 → wait retryBaseDelaySeconds * 4  (e.g. 120 s) → FAILED
 * </pre></p>
 *
 * <p><b>Observability:</b> {@code last_attempted_at} is stamped on every
 * attempt so ops dashboards can detect stale events without parsing logs.</p>
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    private final DSLContext dsl;
    private final int maxRetries;
    private final long retryBaseDelaySeconds;

    public OutboxPoller(
            DSLContext dsl,
            @Value("${outbox.poll.max-retries:3}") int maxRetries,
            @Value("${outbox.poll.retry-base-delay-seconds:30}") long retryBaseDelaySeconds) {
        this.dsl = dsl;
        this.maxRetries = maxRetries;
        this.retryBaseDelaySeconds = retryBaseDelaySeconds;
    }

    /**
     * Poll interval driven by {@code fixedDelayString} so it is fully configurable
     * without recompilation. Defaults to 5 seconds.
     */
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

    /**
     * Fetches up to 10 PENDING events that are ready to be processed.
     *
     * <p>The {@code next_retry_at IS NULL OR next_retry_at <= NOW()} predicate
     * skips events that are still within their exponential back-off window.
     * {@code FOR UPDATE SKIP LOCKED} prevents concurrent poller instances from
     * picking up the same rows. Package-private for testing.</p>
     */
    public List<PaymentOutboxRecord> fetchPendingBatch() {
        OffsetDateTime now = OffsetDateTime.now();
        return dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                .where(PaymentOutbox.PAYMENT_OUTBOX.STATUS.eq(STATUS_PENDING))
                .and(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT.isNull()
                        .or(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT.lessOrEqual(now)))
                .orderBy(PaymentOutbox.PAYMENT_OUTBOX.CREATED_AT.asc())
                .limit(10)
                .forUpdate()
                .skipLocked()
                .fetchInto(PaymentOutboxRecord.class);
    }

    /**
     * Processes a single outbox event within its own transaction.
     *
     * <p>On success: sets {@code status=PROCESSED}, {@code processed_at},
     * and {@code last_attempted_at}.</p>
     *
     * <p>On failure: increments {@code retry_count}, records {@code error_message},
     * stamps {@code last_attempted_at}, and schedules the next attempt via
     * {@code next_retry_at} using exponential back-off. Once {@code maxRetries}
     * is reached the event moves to {@code FAILED} permanently.</p>
     */
    @Transactional
    public void processSingle(String eventId, PaymentOutboxRecord event) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            // TODO: replace with real event dispatch (Kafka, HTTP callback, etc.)
            log.info("Processing outbox event id={} type={} aggregateId={}",
                    event.getId(), event.getType(), event.getAggregateId());

            dsl.update(PaymentOutbox.PAYMENT_OUTBOX)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.STATUS, STATUS_PROCESSED)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.PROCESSED_AT, now)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.LAST_ATTEMPTED_AT, now)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT, (OffsetDateTime) null)
                    .where(PaymentOutbox.PAYMENT_OUTBOX.ID.eq(event.getId()))
                    .execute();

        } catch (Exception ex) {
            int nextRetry = event.getRetryCount() + 1;
            boolean exhausted = nextRetry >= maxRetries;
            String newStatus = exhausted ? STATUS_FAILED : STATUS_PENDING;

            // Exponential back-off: base * 2^(nextRetry - 1)
            // e.g. base=30s → 30 s, 60 s, 120 s
            OffsetDateTime nextRetryAt = exhausted
                    ? null
                    : now.plusSeconds(retryBaseDelaySeconds * (1L << (nextRetry - 1)));

            log.warn("Outbox event id={} processing failed (attempt {}/{}): {}",
                    eventId, nextRetry, maxRetries, ex.getMessage());

            dsl.update(PaymentOutbox.PAYMENT_OUTBOX)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.RETRY_COUNT, nextRetry)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.ERROR_MESSAGE, ex.getMessage())
                    .set(PaymentOutbox.PAYMENT_OUTBOX.STATUS, newStatus)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.LAST_ATTEMPTED_AT, now)
                    .set(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT, nextRetryAt)
                    .where(PaymentOutbox.PAYMENT_OUTBOX.ID.eq(event.getId()))
                    .execute();
        }
    }
}
