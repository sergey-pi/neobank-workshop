package com.neobank.paymentservice.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentOrderResponse(
        UUID id,
        UUID userId,
        String type,
        String status,
        long amount,
        String currency,
        String externalReference,
        OffsetDateTime createdAt) {
}
