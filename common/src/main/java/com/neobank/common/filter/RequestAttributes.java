package com.neobank.common.filter;

import com.neobank.common.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class RequestAttributes {

    static final String USER_ID_ATTR = "userId";

    private RequestAttributes() {
    }

    /**
     * Extracts the authenticated userId from the request attribute set by {@link BearerTokenFilter}.
     * Returns {@code null} if the request was not authenticated (no Bearer token).
     */
    public static UUID getUserId(HttpServletRequest request) {
        return (UUID) request.getAttribute(USER_ID_ATTR);
    }

    /**
     * Like {@link #getUserId} but throws {@link UnauthorizedException} if no userId is present.
     */
    public static UUID requireUserId(HttpServletRequest request) {
        UUID userId = getUserId(request);
        if (userId == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return userId;
    }
}
