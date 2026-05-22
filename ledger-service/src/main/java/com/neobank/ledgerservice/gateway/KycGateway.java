package com.neobank.ledgerservice.gateway;

import com.neobank.common.exception.ForbiddenException;
import com.neobank.common.model.KycStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

/**
 * Calls user-service to verify a user's KYC status before allowing transfers.
 *
 * <p>Uses synchronous RestClient with explicit connect (2 s) and read (5 s) timeouts
 * to prevent thread exhaustion if user-service is slow or unresponsive.</p>
 *
 * <p>This gateway is intentionally called <em>outside</em> the {@code @Transactional}
 * boundary of {@code LedgerService.transfer()} — holding an open DB connection during
 * a network call would exhaust the connection pool under load.</p>
 */
@Component
public class KycGateway {

    private static final Logger log = LoggerFactory.getLogger(KycGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public KycGateway(@Value("${services.user-service.base-url}") String userServiceBaseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl(userServiceBaseUrl)
                .requestFactory(factory)
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

            if (response == null || response.kycStatus() != KycStatus.APPROVED) {
                KycStatus status = response == null ? null : response.kycStatus();
                throw new ForbiddenException(
                        "Transfer denied: KYC not approved for user " + userId + " (status: " + status + ")");
            }
        } catch (ForbiddenException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.warn("KYC check failed for user {} — failing open: {}", userId, ex.getMessage());
        }
    }

    private record KycStatusResponse(UUID userId, KycStatus kycStatus) {
    }
}
