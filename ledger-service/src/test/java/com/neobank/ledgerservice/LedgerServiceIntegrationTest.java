package com.neobank.ledgerservice;

import com.neobank.ledgerservice.dto.CreateAccountRequest;
import com.neobank.ledgerservice.dto.TransferRequest;
import com.neobank.ledgerservice.gateway.KycGateway;
import com.neobank.ledgerservice.jooq.tables.Balances;
import tools.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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

    @MockitoBean
    private KycGateway kycGateway;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        doNothing().when(kycGateway).requireKycApproved(any());
    }

    private RequestPostProcessor authenticatedAs(UUID userId) {
        return request -> {
            request.setAttribute("userId", userId);
            return request;
        };
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

    /**
     * Directly sets the available balance for a test account via jOOQ.
     * Bypasses double-entry bookkeeping for test setup purposes only.
     */
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
    void transfer_insufficientFunds_returnsError() throws Exception {
        UUID senderAccount = createAccount(UUID.randomUUID());
        UUID receiverAccount = createAccount(UUID.randomUUID());

        TransferRequest request = new TransferRequest(
                senderAccount, receiverAccount, 1000L, "USD", "Test transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void transfer_exceedsPerTransactionLimit_returns422() throws Exception {
        UUID senderAccount = createAccount(UUID.randomUUID());
        UUID receiverAccount = createAccount(UUID.randomUUID());

        TransferRequest request = new TransferRequest(
                senderAccount, receiverAccount, 2_000_000L, "USD", "Oversized transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
    }

    @Test
    void transfer_kycNotApproved_returns403() throws Exception {
        UUID senderAccount = createAccount(UUID.randomUUID());
        UUID receiverAccount = createAccount(UUID.randomUUID());

        doThrow(new com.neobank.common.exception.ForbiddenException("KYC not approved"))
                .when(kycGateway).requireKycApproved(any());

        TransferRequest request = new TransferRequest(
                senderAccount, receiverAccount, 100L, "USD", "KYC blocked transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transfer_exceedsDailySpendLimit_returns422() throws Exception {
        UUID sender = createAccount(UUID.randomUUID());
        UUID receiver = createAccount(UUID.randomUUID());
        // Fund enough for 6 transfers of 900_000 (5_400_000 total > 5_000_000 daily limit)
        fundAccount(sender, 6_000_000L);

        TransferRequest request = new TransferRequest(sender, receiver, 900_000L, "USD", "daily spend test");
        String body = objectMapper.writeValueAsString(request);

        // 5 transfers of 900_000 = 4_500_000 — under the 5_000_000 daily limit
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/transactions/transfer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        // 6th transfer: 4_500_000 + 900_000 = 5_400_000 — exceeds daily limit
        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("daily spend limit")));
    }

    @Test
    void getAccounts_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk());
    }

    @Test
    void getTransactions_returnsAmountCurrencyAndDirection() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID senderAccount = createAccount(userId);
        UUID receiverAccount = createAccount(UUID.randomUUID());
        fundAccount(senderAccount, 5_000L);

        TransferRequest request = new TransferRequest(
                senderAccount, receiverAccount, 1_000L, "USD", "History transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/transactions")
                        .with(authenticatedAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].amount").value(1000))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"));
    }
}
