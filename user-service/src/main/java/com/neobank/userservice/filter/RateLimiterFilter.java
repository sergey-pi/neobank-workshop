package com.neobank.userservice.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window in-memory rate limiter applied to POST /api/v1/users/register.
 * Keyed by client IP. State is per-instance (not suitable for multi-node without Redis).
 */
@Component
public class RateLimiterFilter implements Filter {

    private static final String RATE_LIMITED_PATH = "/api/v1/users/register";

    @Value("${security.rate-limit.requests-per-minute:10}")
    private int requestsPerMinute;

    private final ConcurrentHashMap<String, long[]> timestamps = new ConcurrentHashMap<>();

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
                httpResp.setHeader("Retry-After", "60");
                httpResp.setContentType("application/json");
                httpResp.getWriter().write(
                        "{\"status\":429,\"code\":\"TOO_MANY_REQUESTS\","
                        + "\"detail\":\"Rate limit exceeded. Try again in 60 seconds.\"}");
                return;
            }
        }
        chain.doFilter(req, resp);
    }

    private boolean isRateLimited(String clientIp) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L;

        timestamps.compute(clientIp, (ip, existing) -> {
            long[] base = existing == null ? new long[0] : existing;
            long[] filtered = Arrays.stream(base).filter(t -> t > windowStart).toArray();
            long[] updated = new long[filtered.length + 1];
            System.arraycopy(filtered, 0, updated, 0, filtered.length);
            updated[filtered.length] = now;
            return updated;
        });

        long[] window = timestamps.get(clientIp);
        return window != null && window.length > requestsPerMinute;
    }

    private String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
