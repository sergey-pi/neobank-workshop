package com.neobank.common.filter;

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
import java.util.UUID;

public final class BearerTokenFilter implements Filter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_BODY =
            "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"detail\":\"Invalid or expired token\"}";

    private final SecretKey signingKey;

    public BearerTokenFilter(String jwtSecret) {
        this.signingKey = jwtSecret == null || jwtSecret.isBlank()
                ? null
                : Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
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
            httpRequest.setAttribute("userId", UUID.fromString(claims.getSubject()));
            httpRequest.setAttribute("userEmail", claims.get("email", String.class));
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(UNAUTHORIZED_BODY);
        }
    }
}
