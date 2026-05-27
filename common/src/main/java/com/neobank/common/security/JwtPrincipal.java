package com.neobank.common.security;

import io.jsonwebtoken.Claims;

import java.util.UUID;

/**
 * Immutable principal derived from a validated JWT. Stored as a single request attribute
 * by {@link com.neobank.common.filter.BearerTokenFilter} so downstream controllers and
 * services never need to re-parse the token.
 *
 * <p>New JWT claims should be added here and extracted in
 * {@link #fromClaims(Claims)} — no filter changes required.
 */
public record JwtPrincipal(UUID userId, String email) {

    public static JwtPrincipal fromClaims(Claims claims) {
        return new JwtPrincipal(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class));
    }
}
