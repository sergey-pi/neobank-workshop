package com.neobank.common.filter;

import com.neobank.common.security.JwtPrincipal;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BearerTokenFilterTest {

    private static final String SECRET = "dev-secret-change-in-production-min-256-bits";

    @Test
    void doFilter_setsPrincipalIncludingJtiForValidToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = token(userId, "user@example.com", jti);
        BearerTokenFilter filter = new BearerTokenFilter(SECRET, ignored -> false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock();

        filter.doFilter(request, response, chain);

        JwtPrincipal principal = (JwtPrincipal) request.getAttribute("principal");
        assertThat(principal).isEqualTo(new JwtPrincipal(userId, "user@example.com", jti));
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_returnsUnauthorizedForBlacklistedToken() throws Exception {
        String token = token(UUID.randomUUID(), "user@example.com", UUID.randomUUID().toString());
        BearerTokenFilter filter = new BearerTokenFilter(SECRET, ignored -> true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    private static String token(UUID userId, String email, String jti) {
        SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant issuedAt = Instant.now();
        return Jwts.builder()
                .id(jti)
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(15, ChronoUnit.MINUTES)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}
