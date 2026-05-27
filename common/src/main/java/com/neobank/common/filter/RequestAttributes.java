package com.neobank.common.filter;

import com.neobank.common.exception.UnauthorizedException;
import com.neobank.common.security.JwtPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class RequestAttributes {

    static final String PRINCIPAL_ATTR = "principal";

    private RequestAttributes() {
    }

    /**
     * Extracts the {@link JwtPrincipal} set by {@link BearerTokenFilter}.
     * Returns {@code null} if the request was not authenticated (no Bearer token).
     */
    public static JwtPrincipal getPrincipal(HttpServletRequest request) {
        return (JwtPrincipal) request.getAttribute(PRINCIPAL_ATTR);
    }

    /**
     * Like {@link #getPrincipal} but throws {@link UnauthorizedException} if not present.
     */
    public static JwtPrincipal requirePrincipal(HttpServletRequest request) {
        JwtPrincipal principal = getPrincipal(request);
        if (principal == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return principal;
    }

    /**
     * Convenience method — returns the authenticated userId.
     * Throws {@link UnauthorizedException} if not authenticated.
     */
    public static UUID requireUserId(HttpServletRequest request) {
        return requirePrincipal(request).userId();
    }
}
