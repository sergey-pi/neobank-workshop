package com.neobank.ledgerservice.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String reference,
        String type,
        String status,
        String description,
        OffsetDateTime createdAt) {
}
