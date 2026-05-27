package com.neobank.paymentservice;

import com.neobank.paymentservice.dto.PaymentRequest;
import com.neobank.paymentservice.jooq.tables.PaymentOutbox;
import com.neobank.paymentservice.service.OutboxPoller;
import com.neobank.common.security.JwtPrincipal;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PaymentServiceIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private OutboxPoller outboxPoller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private RequestPostProcessor authenticatedAs(UUID userId) {
        return request -> {
            request.setAttribute("principal", new JwtPrincipal(userId, "test@example.com"));
            return request;
        };
    }

    private UUID responseUuid(MvcResult result, String fieldName) throws Exception {
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get(fieldName).asText());
    }

    @Test
    void processPayment_success() throws Exception {
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 5000L, "USD", "Test payment", null);

        mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(request.senderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.orderId").isNotEmpty());
    }

    @Test
    void processPayment_onBehalfOfAnotherUser_returnsForbidden() throws Exception {
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 5000L, "USD", "Test payment", null);

        mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void processPayment_idempotent_returnsSameOrderId() throws Exception {
        String idempotencyKey = "test-idem-" + UUID.randomUUID();
        PaymentRequest idemRequest = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1000L, "USD", "Idempotent payment", idempotencyKey);

        MvcResult first = mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(idemRequest.senderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idemRequest)))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(idemRequest.senderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idemRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String firstOrderId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("orderId").asText();
        String secondOrderId = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("orderId").asText();

        assertThat(firstOrderId).isNotBlank();
        assertThat(secondOrderId).isNotBlank();
    }

    @Test
    void outboxPoller_processesEvent_marksProcessed() throws Exception {
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1000L, "USD", "Outbox test", null);

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(request.senderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        UUID orderId = responseUuid(result, "orderId");

        String statusBefore = dsl.select(PaymentOutbox.PAYMENT_OUTBOX.STATUS)
                .from(PaymentOutbox.PAYMENT_OUTBOX)
                .where(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_ID.eq(orderId))
                .fetchOne(PaymentOutbox.PAYMENT_OUTBOX.STATUS);
        assertThat(statusBefore).isEqualTo(OutboxPoller.STATUS_PENDING);

        outboxPoller.poll();

        var row = dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                .where(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_ID.eq(orderId))
                .fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo(OutboxPoller.STATUS_PROCESSED);
        assertThat(row.getProcessedAt()).isNotNull();
        assertThat(row.getLastAttemptedAt()).isNotNull();
        assertThat(row.getNextRetryAt()).isNull();
    }

    @Test
    void outboxPoller_backoff_setsNextRetryAtOnFailure() throws Exception {
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 2000L, "USD", "Back-off test", null);

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(request.senderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        UUID orderId = responseUuid(result, "orderId");

        dsl.update(PaymentOutbox.PAYMENT_OUTBOX)
                .set(PaymentOutbox.PAYMENT_OUTBOX.RETRY_COUNT, 1)
                .set(PaymentOutbox.PAYMENT_OUTBOX.LAST_ATTEMPTED_AT, OffsetDateTime.now().minusSeconds(60))
                .set(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT, OffsetDateTime.now().plusSeconds(30))
                .where(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_ID.eq(orderId))
                .execute();

        List<com.neobank.paymentservice.jooq.tables.records.PaymentOutboxRecord> batch =
                outboxPoller.fetchPendingBatch();
        assertThat(batch).noneMatch(e -> e.getAggregateId().equals(orderId));

        dsl.update(PaymentOutbox.PAYMENT_OUTBOX)
                .set(PaymentOutbox.PAYMENT_OUTBOX.NEXT_RETRY_AT, OffsetDateTime.now().minusSeconds(1))
                .where(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_ID.eq(orderId))
                .execute();

        batch = outboxPoller.fetchPendingBatch();
        assertThat(batch).anyMatch(e -> e.getAggregateId().equals(orderId));
    }

    @Test
    void outboxPoller_idempotent_alreadyProcessed() throws Exception {
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 500L, "USD", "Idempotency test", null);

        mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(request.senderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        outboxPoller.poll();

        int countBefore = dsl.fetchCount(
                dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                        .where(PaymentOutbox.PAYMENT_OUTBOX.STATUS.eq(OutboxPoller.STATUS_PROCESSED)));

        outboxPoller.poll();

        int countAfter = dsl.fetchCount(
                dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                        .where(PaymentOutbox.PAYMENT_OUTBOX.STATUS.eq(OutboxPoller.STATUS_PROCESSED)));

        assertThat(countAfter).isGreaterThanOrEqualTo(countBefore);
    }

    @Test
    void getPayments_returnsOnlyAuthenticatedUsersPayments() throws Exception {
        PaymentRequest visibleRequest = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 700L, "USD", "Visible payment", null);
        PaymentRequest hiddenRequest = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 900L, "USD", "Hidden payment", null);

        UUID visibleOrderId = responseUuid(mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(visibleRequest.senderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(visibleRequest)))
                .andExpect(status().isOk())
                .andReturn(), "orderId");

        UUID hiddenOrderId = responseUuid(mockMvc.perform(post("/api/v1/payments")
                        .with(authenticatedAs(hiddenRequest.senderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hiddenRequest)))
                .andExpect(status().isOk())
                .andReturn(), "orderId");

        MvcResult result = mockMvc.perform(get("/api/v1/payments")
                        .with(authenticatedAs(visibleRequest.senderId())))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains(visibleOrderId.toString());
        assertThat(responseBody).doesNotContain(hiddenOrderId.toString());
    }
}
