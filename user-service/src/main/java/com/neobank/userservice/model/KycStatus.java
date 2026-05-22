package com.neobank.userservice.model;

/**
 * KYC (Know Your Customer) verification status as owned by user-service.
 *
 * <p>This is the authoritative definition of KYC state. It is stored as a plain
 * VARCHAR string at the DB level. Convert on read: {@code KycStatus.valueOf(dbString)};
 * convert on write: {@code status.name()}.</p>
 *
 * <p>Other services (e.g. ledger-service) define their own local copy of this enum
 * and deserialize the JSON wire value independently — they must not share this type.</p>
 */
public enum KycStatus {

    /** User has registered but has not yet completed KYC verification. */
    PENDING,

    /** User has passed KYC verification and may perform transfers. */
    APPROVED,

    /** User failed KYC verification and is blocked from financial operations. */
    REJECTED
}
