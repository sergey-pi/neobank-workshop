package com.neobank.ledgerservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateAccountRequest(
        @NotNull UUID userId,
        @NotBlank String currency,
        String name,
        @NotBlank String type // LIABILITY, ASSET, EQUITY
) {
}
