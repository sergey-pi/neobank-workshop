package com.neobank.paymentservice.service;

import com.neobank.paymentservice.jooq.tables.records.PaymentOutboxRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link PaymentEventPublisher} that logs the event.
 *
 * <p>Replace this with a Kafka or HTTP implementation before go-live.
 * Annotated with {@link ConditionalOnMissingBean} so a production implementation
 * registered as a Spring bean automatically takes precedence.</p>
 */
@Component
@ConditionalOnMissingBean(value = PaymentEventPublisher.class,
        ignored = LoggingPaymentEventPublisher.class)
public class LoggingPaymentEventPublisher implements PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingPaymentEventPublisher.class);

    @Override
    public void publish(PaymentOutboxRecord record) {
        // TODO: replace with Kafka producer or HTTP webhook for production
        log.info("DISPATCH event id={} type={} aggregateId={} payload={}",
                record.getId(), record.getType(), record.getAggregateId(), record.getPayload());
    }
}
