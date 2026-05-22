package com.neobank.userservice.filter;

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

/**
 * Validates the {@code X-Service-Key} header on the internal {@code /kyc-status} endpoint.
 *
 * <p>The KYC status endpoint is used by ledger-service for service-to-service calls and
 * must not be accessible by unauthenticated external callers. This filter rejects requests
 * to {@code {userId}/kyc-status} that do not carry the shared internal service key.</p>
 *
 * <p>When {@code security.internal-service-key} is empty or not configured (e.g. in local dev
 * or tests), this filter is disabled and all requests pass through.</p>
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
        if (!internalServiceKey.equals(provided)) {
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
}
