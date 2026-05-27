package com.neobank.userservice;

import com.neobank.userservice.dto.LoginRequest;
import com.neobank.userservice.dto.UserRegistrationRequest;
import com.neobank.userservice.service.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class UserServiceIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOperations;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        valueOperations = mock();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private UserRegistrationRequest buildRequest(String email) {
        return new UserRegistrationRequest(
                email,
                "password123",
                "Alice",
                "Smith",
                "+1" + System.currentTimeMillis(),
                LocalDate.of(1990, 1, 15),
                "US",
                "123 Main St",
                "New York",
                "10001"
        );
    }

    private LoginRequest buildLoginRequest(String email, String password) {
        return new LoginRequest(email, password);
    }

    @Test
    void registerUser_success() throws Exception {
        String email = "alice+" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void registerUser_duplicateEmail_returnsError() throws Exception {
        String email = "duplicate+" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(email))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(email))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void login_success_returnsJwt() throws Exception {
        String email = "login+" + UUID.randomUUID() + "@example.com";

        MvcResult registrationResult = mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(email))))
                .andExpect(status().isOk())
                .andReturn();

        String userId = objectMapper.readTree(registrationResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildLoginRequest(email, "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void logout_success_blacklistsTokenJti() throws Exception {
        String email = "logout+" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(email))))
                .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildLoginRequest(email, "password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();
        String jti = jwtUtil.validateAndParse(accessToken).getId();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        verify(valueOperations).set(eq("jwt:blacklist:" + jti), eq("1"), any(Duration.class));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        String email = "bad-password+" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(email))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildLoginRequest(email, "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_wrongEmail_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildLoginRequest("missing+" + UUID.randomUUID() + "@example.com", "password123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getKycStatus_newUser_returnsPending() throws Exception {
        String email = "kyc+" + UUID.randomUUID() + "@example.com";

        MvcResult result = mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(email))))
                .andExpect(status().isOk())
                .andReturn();

        UUID userId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/users/{userId}/kyc-status", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"));
    }

    @Test
    void getKycStatus_unknownUser_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/{userId}/kyc-status", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void getUsers_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk());
    }
}
