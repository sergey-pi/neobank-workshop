package com.neobank.ledgerservice.gateway;

import com.neobank.common.exception.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Calls user-service to verify a user's KYC status before allowing transfers.
 * Uses synchronous RestClient with a short timeout configured at bean level.
 */
@Component
public class KycGateway {

    private static final Logger log = LoggerFactory.getLogger(KycGateway.class);
    private static final String KYC_APPROVED = "APPROVED";

    private final RestClient restClient;

    public KycGateway(@Value("${services.user-service.base-url:http://localhost:8081}") String userServiceBaseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(userServiceBaseUrl)
                .build();
    }

    /**
     * Throws {@link ForbiddenException} if the user is not KYC-approved.
     * On connectivity failure, fails open (logs warning, allows transfer) to avoid a
     * hard dependency on user-service availability in the MVP.
     */
    public void requireKycApproved(UUID userId) {
        try {
            KycStatusResponse response = restClient.get()
                    .uri("/api/v1/users/{userId}/kyc-status", userId)
                    .retrieve()
                    .body(KycStatusResponse.class);

            if (response == null || !KYC_APPROVED.equalsIgnoreCase(response.kycStatus())) {
                String status = response == null ? "unknown" : response.kycStatus();
                throw new ForbiddenException(
                        "Transfer denied: KYC not approved for user " + userId + " (status: " + status + ")");
            }
        } catch (ForbiddenException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.warn("KYC check failed for user {} — failing open: {}", userId, ex.getMessage());
        }
    }

    private record KycStatusResponse(UUID userId, String kycStatus) {
    }
}
