package com.neobank.paymentservice.service;

import com.neobank.paymentservice.jooq.tables.PaymentOutbox;
import com.neobank.paymentservice.jooq.tables.records.PaymentOutboxRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Polls {@code payment_outbox} for PENDING events and dispatches them.
 *
 * <p><b>Concurrency safety:</b> Each event is fetched and processed in a single
 * transaction. The {@code SELECT FOR UPDATE SKIP LOCKED} lock is held through
 * the dispatch and status UPDATE, so concurrent poller instances can never
 * double-process the same row — the lock is only released on commit.</p>
 *
 * <p><b>Transaction management:</b> {@code poll()} is intentionally not
 * transactional. Each event gets its own transaction via {@link TransactionTemplate}
 * injected in the constructor. {@code @Transactional} on a method called via
 * {@code this} would bypass the Spring proxy (self-invocation), silently making
 * the annotation a no-op at runtime.</p>
 *
 * <p><b>Exponential back-off:</b> on failure, {@code next_retry_at} is set to
 * {@code now + retryBaseDelaySeconds * 2^(retryCount - 1)}:
 * <pre>
 *   attempt 1 -> wait retryBaseDelaySeconds      (e.g. 30 s)
 *   attempt 2 -> wait retryBaseDelaySeconds * 2   (e.g. 60 s)
 *   attempt 3 -> wait retryBaseDelaySeconds * 4   (e.g. 120 s) -> FAILED
 * </pre></p>
 *
 * <p><b>Observability:</b> {@code last_attempted_at} is stamped on every attempt
 * so ops dashboards can alert on stale events without parsing logs.</p>
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    private static final int BATCH_SIZE = 10;

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final PaymentEventPublisher eventPublisher;
    private final int maxRetries;
    private final long retryBaseDelaySeconds;

    public OutboxPoller(
            DSLContext dsl,
            PlatformTransactionManager txManager,
            PaymentEventPublisher eventPublisher,
            @Value("${outbox.poll.max-retries:3}") int maxRetries,
            @Value("${outbox.poll.retry-base-delay-seconds:30}") long retryBaseDelaySeconds) {
        this.dsl = dsl;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.eventPublisher = eventPublisher;
        this.maxRetries = maxRetries;
        this.retryBaseDelaySeconds = retryBaseDelaySeconds;
    }

    /**
     * Scheduler entry point. Not transactional — each event gets its own
     * transaction inside {@link #pollOne}.
     */
    @Scheduled(fixedDelayString = "${outbox.poll.interval-ms:5000}")
    public void poll() {
        int processed = 0;
        while (processed < BATCH_SIZE && pollOne()) {
            processed++;
        }
        if (processed > 0) {
            log.debug("Outbox poller processed {} event(s)", processed);
        }
    }

    /**
     * Selects, dispatches, and updates exactly one PENDING event in a single transaction.
     *
     * <p>The {@code SELECT FOR UPDATE SKIP LOCKED} is held for the full duration of the
     * transaction through dispatch and status UPDATE, so concurrent poller instances
     * cannot pick up the same row.</p>
     *
     * @return {@code true} if an event was found and processed, {@code false} if none remain
     */
    private boolean pollOne() {
        Boolean result = transactionTemplate.execute(txStatus -> {
            OffsetDateTime now = OffsetDateTime.now();

            PaymentOutboxRecord event = dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                    .where(PaymentOutbox.PAYMENT_OUTBOX.STATUS.eq(STATUS_PENDING))
                    .and(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT.isNull()
                            .or(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT.lessOrEqual(now)))
                    .orderBy(PaymentOutbox.PAYMENT_OUTBOX.CREATED_AT.asc())
                    .limit(1)
                    .forUpdate()
                    .skipLocked()
                    .fetchOneInto(PaymentOutboxRecord.class);

            if (event == null) {
                return false;
            }

            executeDispatch(event.getId().toString(), event);
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    /**
     * Returns PENDING events ready for processing, without locking rows.
     *
     * <p>Used for observability checks and test assertions. Do NOT use for
     * production processing — use {@link #poll()} which locks each row
     * within a transaction.</p>
     */
    public List<PaymentOutboxRecord> fetchPendingBatch() {
        OffsetDateTime now = OffsetDateTime.now();
        return dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                .where(PaymentOutbox.PAYMENT_OUTBOX.STATUS.eq(STATUS_PENDING))
                .and(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT.isNull()
                        .or(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT.lessOrEqual(now)))
                .orderBy(PaymentOutbox.PAYMENT_OUTBOX.CREATED_AT.asc())
                .limit(BATCH_SIZE)
                .fetchInto(PaymentOutboxRecord.class);
    }

    /**
     * Dispatches the event and updates its outbox row. Runs inside a transaction
     * opened by {@link #pollOne}.
     *
     * <p>On success: {@code status=PROCESSED}, {@code processed_at},
     * {@code last_attempted_at} stamped, {@code next_retry_at} cleared.</p>
     *
     * <p>On failure: {@code retry_count} incremented, {@code error_message} stored,
     * {@code last_attempted_at} stamped, {@code next_retry_at} set for back-off.
     * After {@code maxRetries} attempts the row moves permanently to
     * {@code FAILED}.</p>
     */
    private void executeDispatch(String eventId, PaymentOutboxRecord event) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            eventPublisher.publish(event);

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

            // Exponential back-off: base * 2^(nextRetry - 1), e.g. 30 s -> 60 s -> 120 s
            OffsetDateTime nextRetryAt = exhausted
                    ? null
                    : now.plusSeconds(retryBaseDelaySeconds * (1L << (nextRetry - 1)));

            log.warn("Outbox event id={} failed (attempt {}/{}): {}",
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
