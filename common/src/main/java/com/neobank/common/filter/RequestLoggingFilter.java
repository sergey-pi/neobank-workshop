package com.neobank.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs one access line per request: method, URI, status and duration.
 *
 * <p>The request URI is attacker-controlled, so it is sanitized before logging to prevent
 * log injection (forged log lines via CR/LF, terminal escape sequences via ESC, NEL,
 * U+2028/U+2029 line separators, etc.).
 */
@Order(2)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("REQUEST");

    /**
     * Allow-list of printable ASCII. Anything outside it (all C0/C1 control characters,
     * DEL, Unicode line separators and other non-ASCII) is replaced. An allow-list is used
     * instead of a deny-list such as {@code [\r\n]} or {@code \p{Cntrl}} because those miss
     * C1 controls and Unicode line separators; it is also the form CodeQL's
     * {@code java/log-injection} query recognises as a sanitizer.
     */
    private static final String NON_PRINTABLE_ASCII = "[^\\x20-\\x7E]";
    private static final String REPLACEMENT = "_";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            String sanitizedUri = sanitize(request.getRequestURI());
            log.info("{} {} -> {} ({}ms)",
                    request.getMethod(), sanitizedUri,
                    response.getStatus(), duration);
        }
    }

    /**
     * Replaces every character that is not printable ASCII with {@value #REPLACEMENT}.
     *
     * @param value raw, untrusted value (may be {@code null})
     * @return a single-line, control-character-free string safe to log
     */
    static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll(NON_PRINTABLE_ASCII, REPLACEMENT);
    }
}
