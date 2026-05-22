package com.neobank.userservice;

import com.neobank.userservice.dto.UserRegistrationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
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
