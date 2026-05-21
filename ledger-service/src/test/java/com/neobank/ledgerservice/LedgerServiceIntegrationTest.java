package com.neobank.ledgerservice;

import com.neobank.ledgerservice.dto.CreateAccountRequest;
import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.jooq.tables.Balances;
import org.jooq.DSLContext;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class LedgerServiceIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DSLContext dsl;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private UUID createAccount(UUID userId) throws Exception {
        return createAccountWithCurrency(userId, "USD");
    }

    private UUID createAccountWithCurrency(UUID userId, String currency) throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(userId, currency, "Main Wallet", "LIABILITY");

        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.availableAmount").value(0))
                .andReturn();

        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void fundAccount(UUID accountId, long amount) {
        dsl.update(Balances.BALANCES)
                .set(Balances.BALANCES.AVAILABLE_AMOUNT, amount)
                .where(Balances.BALANCES.ACCOUNT_ID.eq(accountId))
                .execute();
    }

    @Test
    void createAccount_success() throws Exception {
        createAccount(UUID.randomUUID());
    }

    @Test
    void transfer_success() throws Exception {
        UUID sender = createAccount(UUID.randomUUID());
        UUID receiver = createAccount(UUID.randomUUID());
        fundAccount(sender, 5000L);

        TransferRequest request = new TransferRequest(sender, receiver, 1000L, "USD", "Test transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.transactionId").isNotEmpty());
    }

    @Test
    void transfer_insufficientFunds_returns400() throws Exception {
        UUID sender = createAccount(UUID.randomUUID());
        UUID receiver = createAccount(UUID.randomUUID());
        // sender has 0 balance — no funding

        TransferRequest request = new TransferRequest(sender, receiver, 1000L, "USD", "Test transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Insufficient funds in account " + sender));
    }

    @Test
    void transfer_nonExistentFromAccount_returns400() throws Exception {
        UUID ghost = UUID.randomUUID();
        UUID receiver = createAccount(UUID.randomUUID());

        TransferRequest request = new TransferRequest(ghost, receiver, 100L, "USD", "Ghost sender");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "From account not found or has no balance: " + ghost));
    }

    @Test
    void transfer_nonExistentToAccount_returns400() throws Exception {
        UUID sender = createAccount(UUID.randomUUID());
        UUID ghost = UUID.randomUUID();
        fundAccount(sender, 5000L);

        TransferRequest request = new TransferRequest(sender, ghost, 100L, "USD", "Ghost receiver");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Destination account not found or has no balance: " + ghost));
    }

    @Test
    void transfer_currencyMismatch_returns400() throws Exception {
        UUID sender = createAccountWithCurrency(UUID.randomUUID(), "USD");
        UUID receiver = createAccountWithCurrency(UUID.randomUUID(), "EUR");
        fundAccount(sender, 5000L);

        TransferRequest request = new TransferRequest(sender, receiver, 100L, "USD", "Cross-currency attempt");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Currency mismatch between accounts: source is USD, destination is EUR"));
    }

    @Test
    void transfer_requestCurrencyMismatch_returns400() throws Exception {
        UUID sender = createAccount(UUID.randomUUID());
        UUID receiver = createAccount(UUID.randomUUID());
        fundAccount(sender, 5000L);

        // Both accounts are USD but request says EUR
        TransferRequest request = new TransferRequest(sender, receiver, 100L, "EUR", "Wrong currency in request");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Request currency EUR does not match account currency USD"));
    }

    @Test
    void getAccounts_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk());
    }

    @Test
    void getTransactions_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isOk());
    }
}

