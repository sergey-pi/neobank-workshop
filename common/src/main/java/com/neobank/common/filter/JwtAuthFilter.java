package com.neobank.common.filter;

import com.neobank.common.security.JwtAuthentication;
import com.neobank.common.security.JwtPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Validates the JWT Bearer token on each request and populates
 * {@link SecurityContextHolder} with a {@link JwtAuthentication}.
 *
 * <p>Requests without a Bearer token pass through unauthenticated;
 * Spring Security's {@code SecurityFilterChain} enforces authorization rules.
 */
public final class JwtAuthFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_BODY =
            "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"detail\":\"Invalid or expired token\"}";

    private final SecretKey signingKey;

    public JwtAuthFilter(String jwtSecret) {
        this.signingKey = (jwtSecret == null || jwtSecret.isBlank())
                ? null
                : Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (signingKey == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            SecurityContextHolder.getContext()
                    .setAuthentication(new JwtAuthentication(JwtPrincipal.fromClaims(claims)));
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write(UNAUTHORIZED_BODY);
        }
    }
}
