package com.neobank.paymentservice.config;

import com.neobank.common.filter.BearerTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for payment-service.
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
            @Value("${jwt.secret:}") String jwtSecret) {
        BearerTokenFilter filter = new BearerTokenFilter(jwtSecret);
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
