package com.neobank.paymentservice.dto;

import java.util.UUID;

public record PaymentResponse(
        UUID orderId,
        String status,
        String message) {
}
