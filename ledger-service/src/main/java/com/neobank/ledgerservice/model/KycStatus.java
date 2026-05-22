package com.neobank.ledgerservice.model;

/**
 * Ledger-service's local view of KYC status, deserialized from the user-service wire response.
 *
 * <p>This enum is intentionally separate from any definition in user-service or the common
 * module. Each service owns its own enum; the wire protocol uses plain JSON strings.
 * Unknown status values from the wire are mapped to {@link #UNKNOWN} and treated as
 * non-approved (fail-safe for compliance).</p>
 */
public enum KycStatus {

    /** User has registered but has not yet completed KYC verification. */
    PENDING,

    /** User has passed KYC verification and may perform transfers. */
    APPROVED,

    /** User failed KYC verification and is blocked from financial operations. */
    REJECTED,

    /**
     * Wire value not recognised by this service version.
     * Treated as non-approved to fail safely.
     */
    UNKNOWN;

    /**
     * Parses a string from the wire, returning {@link #UNKNOWN} for any unrecognised value
     * instead of throwing {@link IllegalArgumentException}.
     */
    public static KycStatus fromWire(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return KycStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
