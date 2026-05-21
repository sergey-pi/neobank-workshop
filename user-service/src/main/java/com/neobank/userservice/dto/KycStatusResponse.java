package com.neobank.userservice.dto;

import java.util.UUID;

public record KycStatusResponse(
        UUID userId,
        String kycStatus) {
}
