package com.neobank.userservice;

import com.neobank.userservice.dto.UserRegistrationRequest;
import com.neobank.userservice.filter.RateLimiterFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link com.neobank.userservice.filter.RateLimiterFilter}.
 *
 * <p>Uses {@code requests-per-minute=3} to keep tests fast — 4 requests suffice
 * to trigger a 429 rather than the default 11.</p>
 *
 * <p>Each test uses a unique {@code X-Forwarded-For} IP address to prevent
 * rate-limit state from leaking between test cases (the filter's map is a
 * singleton and persists for the lifetime of the application context).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "security.rate-limit.requests-per-minute=3",
    "security.rate-limit.trusted-proxy-cidrs=127.0.0.1/32,::1/128"
})
class RateLimiterFilterIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The filter is a plain {@code @Component} (not a Spring Security filter), so
     * {@code webAppContextSetup} does NOT include it automatically — the embedded
     * container's {@code FilterRegistrationBean} does not run in MOCK web-env tests.
     * We inject the bean (which already has {@code requestsPerMinute=3} from
     * {@code @TestPropertySource}) and add it explicitly.
     */
    @Autowired
    private RateLimiterFilter rateLimiterFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(rateLimiterFilter)
                .build();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String register(String ip) throws Exception {
        String email = "rl+" + UUID.randomUUID() + "@example.com";
        UserRegistrationRequest req = new UserRegistrationRequest(
                email, "password123", "Rate", "Limit",
                "+1" + System.nanoTime(), LocalDate.of(1990, 1, 1),
                "US", "1 Test St", "City", "10001");
        return mockMvc.perform(post("/api/v1/users/register")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void registerExpect(String ip, int expectedStatus) throws Exception {
        String email = "rl+" + UUID.randomUUID() + "@example.com";
        UserRegistrationRequest req = new UserRegistrationRequest(
                email, "password123", "Rate", "Limit",
                "+1" + System.nanoTime(), LocalDate.of(1990, 1, 1),
                "US", "1 Test St", "City", "10001");
        mockMvc.perform(post("/api/v1/users/register")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is(expectedStatus));
    }

    // ── tests ────────────────────────────────────────────────────────────────

    /**
     * After 3 successful registrations (the configured limit), the 4th
     * request from the same IP must be rejected with 429 + Retry-After header
     * and a well-formed JSON error body.
     */
    @Test
    void registerUser_exceedsRateLimit_returns429WithRetryAfterHeader() throws Exception {
        String ip = "10.0.1.1";

        // 3 requests — all within limit
        for (int i = 0; i < 3; i++) {
            registerExpect(ip, 200);
        }

        // 4th request — must be rate-limited
        String email = "rl+" + UUID.randomUUID() + "@example.com";
        UserRegistrationRequest req = new UserRegistrationRequest(
                email, "password123", "Rate", "Limit",
                "+1" + System.nanoTime(), LocalDate.of(1990, 1, 1),
                "US", "1 Test St", "City", "10001");

        mockMvc.perform(post("/api/v1/users/register")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.status").value(429));
    }

    /**
     * A rate-limited IP must not affect requests from a different IP.
     */
    @Test
    void rateLimiter_perIpIsolation_blockedIpDoesNotAffectOtherIp() throws Exception {
        String blockedIp = "10.0.2.1";
        String allowedIp = "10.0.2.2";

        // Exhaust limit for blockedIp
        for (int i = 0; i < 3; i++) {
            registerExpect(blockedIp, 200);
        }
        registerExpect(blockedIp, 429);

        // allowedIp has its own independent counter — must still work
        registerExpect(allowedIp, 200);
    }

    /**
     * Rate limiting applies only to POST /api/v1/users/register.
     * GET /api/v1/users must never be rate-limited regardless of request count.
     */
    @Test
    void rateLimiter_onlyAppliesToPostRegisterEndpoint() throws Exception {
        String ip = "10.0.3.1";

        // Exhaust rate limit
        for (int i = 0; i < 3; i++) {
            registerExpect(ip, 200);
        }
        registerExpect(ip, 429);

        // GET /api/v1/users must pass through regardless
        mockMvc.perform(get("/api/v1/users")
                        .header("X-Forwarded-For", ip))
                .andExpect(status().isOk());
    }

    /**
     * When X-Forwarded-For contains multiple IPs (proxy chain), only the
     * first (client) IP is used as the rate-limit key.
     */
    @Test
    void rateLimiter_xForwardedForMultipleIps_usesFirstIpOnly() throws Exception {
        // client IP is 10.0.4.1; proxies are 10.0.4.2 and 10.0.4.3
        String xForwardedFor = "10.0.4.1, 10.0.4.2, 10.0.4.3";

        // Exhaust limit keyed on 10.0.4.1
        for (int i = 0; i < 3; i++) {
            registerExpect(xForwardedFor, 200);
        }

        // 4th should be limited — confirming key = 10.0.4.1, not 10.0.4.2/3
        registerExpect(xForwardedFor, 429);

        // Request with only the second proxy IP in the chain must NOT be limited
        registerExpect("10.0.4.2", 200);
    }
}
