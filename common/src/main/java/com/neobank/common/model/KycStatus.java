package com.neobank.common.model;

/**
 * KYC (Know Your Customer) verification status as maintained by user-service.
 *
 * <p>Stored as a plain VARCHAR string at the DB and API level. This enum provides
 * type-safe comparisons in Java code and prevents magic-string bugs across services.</p>
 */
public enum KycStatus {

    /** User has registered but has not yet completed KYC verification. */
    PENDING,

    /** User has passed KYC verification and may perform transfers. */
    APPROVED,

    /** User failed KYC verification and is blocked from financial operations. */
    REJECTED
}
