package com.neobank.userservice.dto;

import com.neobank.common.model.KycStatus;

import java.util.UUID;

/**
 * Response body for {@code GET /api/v1/users/{userId}/kyc-status}.
 *
 * @param userId    the user's unique identifier
 * @param kycStatus current KYC verification status
 */
public record KycStatusResponse(
        UUID userId,
        KycStatus kycStatus) {
}
