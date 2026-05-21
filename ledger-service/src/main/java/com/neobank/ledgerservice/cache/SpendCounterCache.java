package com.neobank.ledgerservice.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Atomic Redis counters for daily spend per account.
 * Key: {@code spend:{accountId}:{yyyy-MM-dd}}, TTL: 25 hours.
 *
 * <p>Used as a fast-path guard before the SQL query — reduces DB load on
 * high-frequency transfer paths. Falls back silently if Redis is unavailable.
 */
@Component
public class SpendCounterCache {

    private static final Logger log = LoggerFactory.getLogger(SpendCounterCache.class);
    private static final Duration TTL = Duration.ofHours(25);

    private final StringRedisTemplate redis;

    public SpendCounterCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Atomically increments the daily spend counter and returns the new total.
     * Returns -1 on Redis failure (caller should fall back to SQL).
     */
    public long incrementAndGet(UUID accountId, long amount) {
        try {
            String key = key(accountId);
            Long newValue = redis.opsForValue().increment(key, amount);
            redis.expire(key, TTL);
            return newValue == null ? -1L : newValue;
        } catch (Exception ex) {
            log.warn("Redis INCRBY spend:{} failed: {}", accountId, ex.getMessage());
            return -1L;
        }
    }

    /**
     * Returns the current daily spend total, or -1 if Redis is unavailable / key absent.
     */
    public long get(UUID accountId) {
        try {
            String value = redis.opsForValue().get(key(accountId));
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception ex) {
            log.warn("Redis GET spend:{} failed: {}", accountId, ex.getMessage());
            return -1L;
        }
    }

    private String key(UUID accountId) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        return "spend:" + accountId + ":" + today;
    }
}
