package com.neobank.common.security;

import io.jsonwebtoken.Claims;

import java.util.UUID;

public record JwtPrincipal(UUID userId, String email, String jti) {

    public static JwtPrincipal fromClaims(Claims claims) {
        return new JwtPrincipal(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                claims.getId());
    }
}
