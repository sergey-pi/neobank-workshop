package com.neobank.userservice.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed cache for KYC status lookups.
 * Key: {@code kyc:{userId}}, TTL: 5 minutes.
 * All operations fail silently — Redis unavailability must never break the user flow.
 */
@Component
public class KycStatusCache {

    private static final Logger log = LoggerFactory.getLogger(KycStatusCache.class);
    private static final String KEY_PREFIX = "kyc:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public KycStatusCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<String> get(UUID userId) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + userId);
            return Optional.ofNullable(value);
        } catch (Exception ex) {
            log.warn("Redis GET kyc:{} failed — cache miss: {}", userId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(UUID userId, String kycStatus) {
        try {
            redis.opsForValue().set(KEY_PREFIX + userId, kycStatus, TTL);
        } catch (Exception ex) {
            log.warn("Redis SET kyc:{} failed — skipping cache write: {}", userId, ex.getMessage());
        }
    }

    public void evict(UUID userId) {
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (Exception ex) {
            log.warn("Redis DEL kyc:{} failed: {}", userId, ex.getMessage());
        }
    }
}
