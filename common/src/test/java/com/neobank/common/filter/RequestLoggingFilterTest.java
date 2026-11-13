package com.neobank.common.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that {@link RequestLoggingFilter} neutralises log-injection payloads in the
 * request URI before it reaches the log, while leaving ordinary URIs untouched.
 */
class RequestLoggingFilterTest {

    private static final String PRINTABLE_ASCII_PATTERN = "[\\x20-\\x7E]*";

    @Test
    void sanitizeLeavesNormalUriUnchanged() {
        String uri = "/api/v1/transactions?page=0&size=20%0A";
        assertThat(RequestLoggingFilter.sanitize(uri)).isEqualTo(uri);
    }

    @Test
    void sanitizeReplacesForgedLogLine() {
        String uri = "/api/v1/users\r\n2026-01-01 INFO REQUEST GET /admin -> 200";
        assertThat(RequestLoggingFilter.sanitize(uri))
                .isEqualTo("/api/v1/users__2026-01-01 INFO REQUEST GET /admin -> 200");
    }

    /**
     * Each payload is a single control or line-separator character: tab, NUL, ESC (ANSI
     * escape sequences), DEL, NEL (C1 next line), and Unicode line/paragraph separators.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\t", "\u0000", "\u001B[31m", "\u007F", "\u0085", "\u2028", "\u2029"})
    void sanitizeReplacesControlAndLineSeparatorCharacters(String payload) {
        String sanitized = RequestLoggingFilter.sanitize("/api" + payload + "/x");
        assertThat(sanitized).startsWith("/api_").endsWith("/x").matches(PRINTABLE_ASCII_PATTERN);
    }

    @Test
    void sanitizeHandlesNull() {
        assertThat(RequestLoggingFilter.sanitize(null)).isNull();
    }

    @Test
    void filterLogsMaliciousUriWithoutFailing() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api\r\n/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> new RequestLoggingFilter().doFilter(request, response, chain))
                .doesNotThrowAnyException();
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
