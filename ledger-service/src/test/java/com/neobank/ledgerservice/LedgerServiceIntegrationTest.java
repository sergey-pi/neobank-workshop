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

import static org.assertj.core.api.Assertions.assertThat;
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

    private UUID responseUuid(MvcResult result, String fieldName) throws Exception {
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get(fieldName).asText());
    }

    private UUID createAccount(UUID userId) throws Exception {
        return createAccountWithCurrency(userId, "USD");
    }

    private UUID createAccountWithCurrency(UUID userId, String currency) throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(userId, currency, "Main Wallet", "LIABILITY");

        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .with(authenticatedAs(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.availableAmount").value(0))
                .andReturn();

        return responseUuid(result, "id");
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
    void createAccount_forDifferentUser_returnsForbidden() throws Exception {
        UUID tokenUserId = UUID.randomUUID();
        CreateAccountRequest request = new CreateAccountRequest(UUID.randomUUID(), "USD", "Main Wallet", "LIABILITY");

        mockMvc.perform(post("/api/v1/accounts")
                        .with(authenticatedAs(tokenUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transfer_insufficientFunds_returnsError() throws Exception {
        UUID senderUserId = UUID.randomUUID();
        UUID senderAccount = createAccount(senderUserId);
        UUID receiverAccount = createAccount(UUID.randomUUID());

        TransferRequest request = new TransferRequest(
                senderAccount, receiverAccount, 1000L, "USD", "Test transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .with(authenticatedAs(senderUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void transfer_exceedsPerTransactionLimit_returns422() throws Exception {
        UUID senderUserId = UUID.randomUUID();
        UUID senderAccount = createAccount(senderUserId);
        UUID receiverAccount = createAccount(UUID.randomUUID());

        TransferRequest request = new TransferRequest(
                senderAccount, receiverAccount, 2_000_000L, "USD", "Oversized transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .with(authenticatedAs(senderUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
    }

    @Test
    void transfer_kycNotApproved_returns403() throws Exception {
        UUID senderUserId = UUID.randomUUID();
        UUID senderAccount = createAccount(senderUserId);
        UUID receiverAccount = createAccount(UUID.randomUUID());

        doThrow(new com.neobank.common.exception.ForbiddenException("KYC not approved"))
                .when(kycGateway).requireKycApproved(any());

        TransferRequest request = new TransferRequest(
                senderAccount, receiverAccount, 100L, "USD", "KYC blocked transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .with(authenticatedAs(senderUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transfer_fromOtherUsersAccount_returnsForbidden() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID attackerUserId = UUID.randomUUID();
        UUID senderAccount = createAccount(ownerUserId);
        UUID receiverAccount = createAccount(UUID.randomUUID());
        fundAccount(senderAccount, 1_000L);

        TransferRequest request = new TransferRequest(
                senderAccount, receiverAccount, 100L, "USD", "Forbidden transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .with(authenticatedAs(attackerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transfer_exceedsDailySpendLimit_returns422() throws Exception {
        UUID senderUserId = UUID.randomUUID();
        UUID sender = createAccount(senderUserId);
        UUID receiver = createAccount(UUID.randomUUID());
        fundAccount(sender, 6_000_000L);

        TransferRequest request = new TransferRequest(sender, receiver, 900_000L, "USD", "daily spend test");
        String body = objectMapper.writeValueAsString(request);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/transactions/transfer")
                            .with(authenticatedAs(senderUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .with(authenticatedAs(senderUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("daily spend limit")));
    }

    @Test
    void getAccounts_returnsOnlyAuthenticatedUsersAccounts() throws Exception {
        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        UUID firstAccountId = createAccount(firstUserId);
        UUID secondAccountId = createAccount(secondUserId);

        MvcResult result = mockMvc.perform(get("/api/v1/accounts")
                        .with(authenticatedAs(firstUserId)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains(firstAccountId.toString());
        assertThat(responseBody).doesNotContain(secondAccountId.toString());
    }

    @Test
    void getTransactions_returnsOnlyScopedTransactions() throws Exception {
        UUID visibleUserId = UUID.randomUUID();
        UUID visibleSender = createAccount(visibleUserId);
        UUID visibleReceiver = createAccount(UUID.randomUUID());
        fundAccount(visibleSender, 5_000L);

        UUID hiddenUserId = UUID.randomUUID();
        UUID hiddenSender = createAccount(hiddenUserId);
        UUID hiddenReceiver = createAccount(UUID.randomUUID());
        fundAccount(hiddenSender, 5_000L);

        TransferRequest visibleRequest = new TransferRequest(
                visibleSender, visibleReceiver, 1_000L, "USD", "Visible transfer");
        TransferRequest hiddenRequest = new TransferRequest(
                hiddenSender, hiddenReceiver, 1_000L, "USD", "Hidden transfer");

        UUID visibleTransactionId = responseUuid(mockMvc.perform(post("/api/v1/transactions/transfer")
                        .with(authenticatedAs(visibleUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(visibleRequest)))
                .andExpect(status().isOk())
                .andReturn(), "transactionId");

        UUID hiddenTransactionId = responseUuid(mockMvc.perform(post("/api/v1/transactions/transfer")
                        .with(authenticatedAs(hiddenUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hiddenRequest)))
                .andExpect(status().isOk())
                .andReturn(), "transactionId");

        MvcResult result = mockMvc.perform(get("/api/v1/transactions")
                        .with(authenticatedAs(visibleUserId)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains(visibleTransactionId.toString());
        assertThat(responseBody).doesNotContain(hiddenTransactionId.toString());
    }
}
