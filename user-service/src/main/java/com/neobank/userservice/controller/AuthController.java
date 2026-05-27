package com.neobank.userservice.controller;

import com.neobank.common.exception.ServiceUnavailableException;
import com.neobank.common.exception.UnauthorizedException;
import com.neobank.userservice.service.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    public AuthController(JwtUtil jwtUtil, StringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = extractBearerToken(authHeader);
        Claims claims = parseToken(token);
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            throw new UnauthorizedException("Token is missing jti claim");
        }

        Duration ttl = Duration.between(Instant.now(), claims.getExpiration().toInstant());
        if (ttl.isZero() || ttl.isNegative()) {
            throw new UnauthorizedException("Token is expired");
        }

        try {
            redisTemplate.opsForValue().set(BLACKLIST_KEY_PREFIX + jti, "1", ttl);
        } catch (Exception ex) {
            throw new ServiceUnavailableException("Token revocation store is unavailable");
        }
        return ResponseEntity.ok().build();
    }

    private Claims parseToken(String token) {
        try {
            return jwtUtil.validateAndParse(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Missing bearer token");
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw new UnauthorizedException("Missing bearer token");
        }
        return token;
    }
}
