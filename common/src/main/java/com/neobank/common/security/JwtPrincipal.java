package com.neobank.common.security;

import io.jsonwebtoken.Claims;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * Immutable principal derived from a validated JWT. Stored in {@link org.springframework.security.core.context.SecurityContextHolder}
 * via {@link JwtAuthentication}.
 *
 * <p>New JWT claims should be added here and extracted in
 * {@link #fromClaims(Claims)} — no filter changes required.
 */
public record JwtPrincipal(UUID userId, String email) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static JwtPrincipal fromClaims(Claims claims) {
        return new JwtPrincipal(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class));
    }
}
