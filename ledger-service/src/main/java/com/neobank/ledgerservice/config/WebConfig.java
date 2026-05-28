package com.neobank.ledgerservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsAllowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
