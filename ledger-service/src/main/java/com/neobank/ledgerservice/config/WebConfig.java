package com.neobank.ledgerservice.config;

import com.neobank.common.filter.BearerTokenFilter;
import com.neobank.common.security.TokenBlacklistChecker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for ledger-service.
 *
 * <p>Allowed origins are externalized via {@code cors.allowed-origins} in application.yml
 * (env var: {@code CORS_ALLOWED_ORIGINS}). Supports comma-separated values for multi-origin deployments.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins}")
    private String corsAllowedOrigins;

    @Bean
    public FilterRegistrationBean<BearerTokenFilter> bearerTokenFilter(
            @Value("${jwt.secret:}") String jwtSecret,
            StringRedisTemplate redisTemplate) {
        TokenBlacklistChecker checker = jti -> Boolean.TRUE.equals(
                redisTemplate.hasKey("jwt:blacklist:" + jti));
        BearerTokenFilter filter = new BearerTokenFilter(jwtSecret, checker);
        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsAllowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
