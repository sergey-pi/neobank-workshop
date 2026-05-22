package com.neobank.userservice.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sliding-window in-memory rate limiter applied to POST /api/v1/users/register.
 * Keyed by client IP. State is per-instance (not suitable for multi-node without Redis).
 *
 * <p>Memory is bounded by a Caffeine cache with {@code expireAfterWrite(2 minutes)} and
 * {@code maximumSize(100,000)} entries, preventing OOM under a sustained rotation attack.</p>
 *
 * <p>{@code X-Forwarded-For} is only trusted when the direct TCP peer ({@code remoteAddr})
 * is in the configured {@code security.rate-limit.trusted-proxy-cidrs} list. Untrusted
 * sources fall back to {@code getRemoteAddr()}, preventing IP spoofing bypasses.</p>
 */
@Component
public class RateLimiterFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterFilter.class);
    private static final String RATE_LIMITED_PATH = "/api/v1/users/register";
    private static final int RETRY_AFTER_SECONDS = 60;
    private static final long WINDOW_MILLIS = RETRY_AFTER_SECONDS * 1000L;
    private static final String RATE_LIMIT_RESPONSE_BODY = """
            {"status":429,"code":"TOO_MANY_REQUESTS",\
            "detail":"Rate limit exceeded. Try again in %d seconds."}""".formatted(RETRY_AFTER_SECONDS);

    @Value("${security.rate-limit.requests-per-minute:10}")
    private int requestsPerMinute;

    private final List<String> trustedProxyCidrs;

    /** Bounded by 100 k entries; each entry auto-expires after 2 minutes of inactivity. */
    private final Cache<String, long[]> timestamps = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    public RateLimiterFilter(
            @Value("${security.rate-limit.trusted-proxy-cidrs:}") String trustedProxyCidrsRaw) {
        this.trustedProxyCidrs = Arrays.stream(trustedProxyCidrsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        if ("POST".equalsIgnoreCase(httpReq.getMethod())
                && RATE_LIMITED_PATH.equals(httpReq.getRequestURI())) {

            String clientIp = resolveClientIp(httpReq);
            if (isRateLimited(clientIp)) {
                httpResp.setStatus(429);
                httpResp.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
                httpResp.setContentType("application/json");
                httpResp.getWriter().write(RATE_LIMIT_RESPONSE_BODY);
                return;
            }
        }
        chain.doFilter(req, resp);
    }

    private boolean isRateLimited(String clientIp) {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MILLIS;

        long[] updated = timestamps.asMap().compute(clientIp, (ip, existing) -> {
            long[] base = existing == null ? new long[0] : existing;
            long[] filtered = Arrays.stream(base).filter(t -> t > windowStart).toArray();
            long[] next = new long[filtered.length + 1];
            System.arraycopy(filtered, 0, next, 0, filtered.length);
            next[filtered.length] = now;
            return next;
        });

        return updated != null && updated.length > requestsPerMinute;
    }

    /**
     * Returns the real client IP.
     * Trusts {@code X-Forwarded-For} only when the TCP peer is a known reverse proxy.
     * Falls back to {@code getRemoteAddr()} for untrusted sources to prevent spoofing.
     */
    private String resolveClientIp(HttpServletRequest req) {
        String remoteAddr = req.getRemoteAddr();
        if (!trustedProxyCidrs.isEmpty() && isTrustedProxy(remoteAddr)) {
            String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        try {
            InetAddress remote = InetAddress.getByName(remoteAddr);
            for (String cidr : trustedProxyCidrs) {
                if (cidrContains(cidr, remote)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            log.warn("Could not resolve remote address for proxy check: {}", remoteAddr);
        }
        return false;
    }

    private boolean cidrContains(String cidr, InetAddress remote) {
        try {
            String[] parts = cidr.split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            byte[] networkBytes = network.getAddress();
            byte[] remoteBytes = remote.getAddress();
            if (networkBytes.length != remoteBytes.length) {
                return false;
            }
            int bitsRemaining = prefix;
            for (int i = 0; i < networkBytes.length; i++) {
                if (bitsRemaining <= 0) {
                    break;
                }
                int mask = bitsRemaining >= 8 ? 0xFF : (0xFF << (8 - bitsRemaining)) & 0xFF;
                if ((networkBytes[i] & mask) != (remoteBytes[i] & mask)) {
                    return false;
                }
                bitsRemaining -= 8;
            }
            return true;
        } catch (Exception e) {
            log.warn("Invalid CIDR entry '{}': {}", cidr, e.getMessage());
            return false;
        }
    }
}

