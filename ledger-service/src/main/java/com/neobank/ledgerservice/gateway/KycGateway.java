package com.neobank.ledgerservice.gateway;

import com.neobank.common.exception.ForbiddenException;
import com.neobank.common.exception.ServiceUnavailableException;
import com.neobank.ledgerservice.model.KycStatus;
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
    private static final String SERVICE_KEY_HEADER = "X-Service-Key";

    private final RestClient restClient;
    private final String internalServiceKey;

    public KycGateway(
            @Value("${services.user-service.base-url}") String userServiceBaseUrl,
            @Value("${services.internal-service-key:}") String internalServiceKey) {
        this.internalServiceKey = internalServiceKey;
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
     * Throws {@link ServiceUnavailableException} on connectivity failure — transfers are
     * rejected when KYC status cannot be confirmed (fail-closed for compliance).
     */
    public void requireKycApproved(UUID userId) {
        try {
            var request = restClient.get()
                    .uri("/api/v1/users/{userId}/kyc-status", userId);

            if (!internalServiceKey.isEmpty()) {
                request = request.header(SERVICE_KEY_HEADER, internalServiceKey);
            }

            KycStatusResponse response = request.retrieve().body(KycStatusResponse.class);

            KycStatus status = response == null ? KycStatus.UNKNOWN : response.parsedStatus();
            if (status != KycStatus.APPROVED) {
                throw new ForbiddenException(
                        "Transfer denied: KYC not approved for user " + userId + " (status: " + status + ")");
            }
        } catch (ForbiddenException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.error("KYC service unavailable for user {} — failing closed: {}", userId, ex.getMessage());
            throw new ServiceUnavailableException("KYC service unavailable; transfer not permitted");
        }
    }

    /** Wire DTO — uses a raw String so unknown future status values map to {@link KycStatus#UNKNOWN}. */
    private record KycStatusResponse(UUID userId, String kycStatus) {
        KycStatus parsedStatus() {
            return KycStatus.fromWire(kycStatus);
        }
    }
}
