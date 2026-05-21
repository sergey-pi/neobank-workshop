package com.neobank.ledgerservice.config;

import com.neobank.common.filter.RequestLoggingFilter;
import com.neobank.common.filter.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebFilterConfig {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter("ledger-service");
    }

    @Bean
    public RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }
}
