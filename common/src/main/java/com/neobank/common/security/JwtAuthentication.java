package com.neobank.common.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.Collections;

/**
 * Spring Security {@link org.springframework.security.core.Authentication} token
 * populated by {@link com.neobank.common.filter.JwtAuthFilter} after JWT validation.
 * Held in {@link org.springframework.security.core.context.SecurityContextHolder} for
 * the duration of the request.
 */
public final class JwtAuthentication extends AbstractAuthenticationToken {

    private final JwtPrincipal principal;

    public JwtAuthentication(JwtPrincipal principal) {
        super(Collections.emptyList());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public JwtPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }
}
