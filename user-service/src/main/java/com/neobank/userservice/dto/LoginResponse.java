package com.neobank.userservice.dto;

import java.util.UUID;

public record LoginResponse(
        String accessToken,
        UUID userId,
        String email,
        long expiresIn) {
}
