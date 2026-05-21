package com.neobank.ledgerservice.dto;

import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        String status,
        String message) {
}
