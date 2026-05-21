package com.neobank.paymentservice.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency store for payment submissions.
 * Key: {@code idem:{idempotencyKey}}, value: orderId (UUID string), TTL: 24 hours.
 *
 * <p>If Redis is unavailable, returns empty (falls through to DB processing).
 */
@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);
    private static final String KEY_PREFIX = "idem:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public IdempotencyCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Returns the previously stored orderId for this key, if present.
     */
    public Optional<String> get(String idempotencyKey) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
            return Optional.ofNullable(value);
        } catch (Exception ex) {
            log.warn("Redis GET idem:{} failed — cache miss: {}", idempotencyKey, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores orderId for this key. Uses SET NX (only if absent) to avoid overwriting
     * a concurrent insert.
     */
    public void putIfAbsent(String idempotencyKey, String orderId) {
        try {
            redis.opsForValue().setIfAbsent(KEY_PREFIX + idempotencyKey, orderId, TTL);
        } catch (Exception ex) {
            log.warn("Redis SET idem:{} failed: {}", idempotencyKey, ex.getMessage());
        }
    }
}
