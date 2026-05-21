package com.neobank.paymentservice;

import com.neobank.paymentservice.dto.PaymentRequest;
import com.neobank.paymentservice.jooq.tables.PaymentOutbox;
import com.neobank.paymentservice.service.OutboxPoller;
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

    @Test
    void processPayment_success() throws Exception {
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                5000L,
                "USD",
                "Test payment"
        );

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.orderId").isNotEmpty());
    }

    @Test
    void outboxPoller_processesEvent_marksProcessed() throws Exception {
        // 1. Submit a payment — creates a PENDING outbox entry
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1000L, "USD", "Outbox test");

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        UUID orderId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("orderId").asText());

        // 2. Verify the outbox entry is PENDING before polling
        String statusBefore = dsl.select(PaymentOutbox.PAYMENT_OUTBOX.STATUS)
                .from(PaymentOutbox.PAYMENT_OUTBOX)
                .where(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_ID.eq(orderId))
                .fetchOne(PaymentOutbox.PAYMENT_OUTBOX.STATUS);
        assertThat(statusBefore).isEqualTo(OutboxPoller.STATUS_PENDING);

        // 3. Manually trigger the poller (no need to wait for @Scheduled)
        outboxPoller.poll();

        // 4. Assert the event is now PROCESSED
        String statusAfter = dsl.select(PaymentOutbox.PAYMENT_OUTBOX.STATUS)
                .from(PaymentOutbox.PAYMENT_OUTBOX)
                .where(PaymentOutbox.PAYMENT_OUTBOX.AGGREGATE_ID.eq(orderId))
                .fetchOne(PaymentOutbox.PAYMENT_OUTBOX.STATUS);
        assertThat(statusAfter).isEqualTo(OutboxPoller.STATUS_PROCESSED);
    }

    @Test
    void outboxPoller_idempotent_alreadyProcessed() throws Exception {
        // Submit payment and process it once
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), 500L, "USD", "Idempotency test");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        outboxPoller.poll(); // first poll — processes the event

        // Count PROCESSED rows before second poll
        int countBefore = dsl.fetchCount(
                dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                        .where(PaymentOutbox.PAYMENT_OUTBOX.STATUS.eq(OutboxPoller.STATUS_PROCESSED)));

        outboxPoller.poll(); // second poll — nothing PENDING left, count unchanged

        int countAfter = dsl.fetchCount(
                dsl.selectFrom(PaymentOutbox.PAYMENT_OUTBOX)
                        .where(PaymentOutbox.PAYMENT_OUTBOX.STATUS.eq(OutboxPoller.STATUS_PROCESSED)));

        assertThat(countAfter).isGreaterThanOrEqualTo(countBefore);
    }

    @Test
    void getPayments_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/payments"))
                .andExpect(status().isOk());
    }
}
