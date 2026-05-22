package com.neobank.userservice.filter;

import jakarta.annotation.PostConstruct;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates the {@code X-Service-Key} header on the internal {@code /kyc-status} endpoint.
 *
 * <p>The KYC status endpoint is used by ledger-service for service-to-service calls and
 * must not be accessible by unauthenticated external callers. This filter rejects requests
 * to {@code {userId}/kyc-status} that do not carry the shared internal service key.</p>
 *
 * <p><strong>Production requirement:</strong> Set {@code INTERNAL_SERVICE_KEY} to a strong
 * random secret in all environments. When the key is not configured, the filter passes all
 * requests and logs a startup warning — the {@code /kyc-status} endpoint is UNPROTECTED.</p>
 */
@Component
public class InternalServiceKeyFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(InternalServiceKeyFilter.class);
    private static final String KYC_STATUS_SUFFIX = "/kyc-status";
    private static final String SERVICE_KEY_HEADER = "X-Service-Key";
    private static final String UNAUTHORIZED_BODY =
            "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"detail\":\"Missing or invalid service key\"}";

    private final String internalServiceKey;

    public InternalServiceKeyFilter(
            @Value("${security.internal-service-key:}") String internalServiceKey) {
        this.internalServiceKey = internalServiceKey;
    }

    @PostConstruct
    void validate() {
        if (internalServiceKey.isEmpty()) {
            log.warn("SECURITY WARNING: security.internal-service-key is not configured. "
                    + "/kyc-status endpoint is UNPROTECTED. Set INTERNAL_SERVICE_KEY in production.");
        }
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        if (internalServiceKey.isEmpty() || !httpReq.getRequestURI().endsWith(KYC_STATUS_SUFFIX)) {
            chain.doFilter(req, resp);
            return;
        }

        String provided = httpReq.getHeader(SERVICE_KEY_HEADER);
        if (provided == null || !constantTimeEquals(internalServiceKey, provided)) {
            String safeUri = httpReq.getRequestURI().replaceAll("[\r\n]", "_");
            log.warn("Rejected unauthenticated request to {} from {}",
                    safeUri, httpReq.getRemoteAddr());
            httpResp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResp.setContentType("application/json");
            httpResp.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }

        chain.doFilter(req, resp);
    }

    /** Constant-time string comparison to prevent timing oracle attacks. */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
