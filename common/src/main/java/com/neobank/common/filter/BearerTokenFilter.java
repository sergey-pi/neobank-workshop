package com.neobank.common.filter;

import com.neobank.common.security.JwtPrincipal;
import com.neobank.common.security.TokenBlacklistChecker;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BearerTokenFilter implements Filter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_BODY =
            "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"detail\":\"Invalid or expired token\"}";

    private final SecretKey signingKey;
    private final TokenBlacklistChecker blacklistChecker;

    public BearerTokenFilter(String jwtSecret) {
        this(jwtSecret, null);
    }

    public BearerTokenFilter(String jwtSecret, TokenBlacklistChecker blacklistChecker) {
        this.signingKey = jwtSecret == null || jwtSecret.isBlank()
                ? null
                : Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.blacklistChecker = blacklistChecker;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (signingKey == null) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = httpRequest.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (blacklistChecker != null && claims.getId() != null && blacklistChecker.isBlacklisted(claims.getId())) {
                writeUnauthorized(httpResponse);
                return;
            }
            httpRequest.setAttribute("principal", JwtPrincipal.fromClaims(claims));
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            writeUnauthorized(httpResponse);
        }
    }

    private void writeUnauthorized(HttpServletResponse httpResponse) throws IOException {
        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        httpResponse.setContentType("application/json");
        httpResponse.getWriter().write(UNAUTHORIZED_BODY);
    }
}
