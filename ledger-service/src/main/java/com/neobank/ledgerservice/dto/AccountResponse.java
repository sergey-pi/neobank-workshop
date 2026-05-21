package com.neobank.ledgerservice.dto;

import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID userId,
        String currency,
        String name,
        String type,
        String status,
        long availableAmount) {
}
