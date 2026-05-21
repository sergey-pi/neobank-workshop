package com.neobank.paymentservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PaymentRequest(
        @NotNull UUID senderId,
        @NotNull UUID receiverId,
        @NotNull @Min(1) Long amount,
        @NotBlank String currency,
        String description) {
}
