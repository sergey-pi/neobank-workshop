package com.neobank.ledgerservice.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency store for transfer requests.
 *
 * <p>Key: {@code transfer-idem:{idempotencyKey}}, value: transactionId (UUID string), TTL: 24h.
 * If Redis is unavailable, returns empty so the DB-level unique index acts as the
 * authoritative guard.</p>
 */
@Component
public class TransferIdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(TransferIdempotencyCache.class);
    private static final String KEY_PREFIX = "transfer-idem:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public TransferIdempotencyCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Returns the previously stored transactionId for this key, if present. */
    public Optional<String> get(String idempotencyKey) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(KEY_PREFIX + idempotencyKey));
        } catch (Exception ex) {
            log.warn("Redis GET transfer-idem:{} failed — cache miss: {}", sanitize(idempotencyKey), ex.getMessage());
            return Optional.empty();
        }
    }

    /** Stores transactionId for this key using SET NX to avoid overwriting a concurrent insert. */
    public void putIfAbsent(String idempotencyKey, String transactionId) {
        try {
            redis.opsForValue().setIfAbsent(KEY_PREFIX + idempotencyKey, transactionId, TTL);
        } catch (Exception ex) {
            log.warn("Redis SET transfer-idem:{} failed: {}", sanitize(idempotencyKey), ex.getMessage());
        }
    }

    /** Strips newline/carriage-return characters to prevent log injection. */
    private static String sanitize(String value) {
        return value == null ? null : value.replaceAll("[\r\n]", "_");
    }
}
