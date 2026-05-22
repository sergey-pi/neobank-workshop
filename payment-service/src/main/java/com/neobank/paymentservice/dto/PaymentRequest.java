package com.neobank.paymentservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Payload for submitting a new payment order.
 *
 * @param senderId       UUID of the sending user
 * @param receiverId     UUID of the receiving user
 * @param amount         transfer amount in minor units (cents); must be >= 1
 * @param currency       ISO 4217 currency code (e.g. "USD")
 * @param description    optional human-readable memo
 * @param idempotencyKey optional client-generated unique key; if supplied and a prior
 *                       submission with the same key exists in Redis, the original response
 *                       is returned without creating a duplicate order (TTL: 24 h)
 */
public record PaymentRequest(
        @NotNull UUID senderId,
        @NotNull UUID receiverId,
        @NotNull @Min(1) Long amount,
        @NotBlank String currency,
        String description,
        String idempotencyKey) {
}
