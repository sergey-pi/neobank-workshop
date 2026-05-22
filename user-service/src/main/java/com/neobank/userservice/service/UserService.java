package com.neobank.userservice.service;

import com.neobank.common.exception.ConflictException;
import com.neobank.common.exception.NotFoundException;
import com.neobank.common.model.KycStatus;
import com.neobank.userservice.cache.KycStatusCache;
import com.neobank.userservice.dto.KycStatusResponse;
import com.neobank.userservice.dto.UserRegistrationRequest;
import com.neobank.userservice.dto.UserResponse;
import com.neobank.userservice.jooq.tables.UserAddresses;
import com.neobank.userservice.jooq.tables.UserProfiles;
import com.neobank.userservice.jooq.tables.UserSettings;
import com.neobank.userservice.jooq.tables.Users;
import org.jooq.DSLContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Core user management service responsible for registration and profile queries.
 *
 * <p>Registration creates four records atomically:
 * <ol>
 *   <li>{@code users} — credentials and account status</li>
 *   <li>{@code user_profiles} — PII (name, DOB, KYC status = PENDING)</li>
 *   <li>{@code user_addresses} — primary address</li>
 *   <li>{@code user_settings} — default notification and locale preferences</li>
 * </ol>
 *
 * <p>KYC status lookups check the Redis cache first ({@code kyc:{userId}}, TTL 5 min).
 * On a cache miss the value is fetched from {@code user_profiles} and written back.
 * Cache failures are transparent — the service always falls back to the DB.
 */
@Service
public class UserService {

    private final DSLContext dsl;
    private final PasswordEncoder passwordEncoder;
    private final KycStatusCache kycStatusCache;

    public UserService(DSLContext dsl, PasswordEncoder passwordEncoder, KycStatusCache kycStatusCache) {
        this.dsl = dsl;
        this.passwordEncoder = passwordEncoder;
        this.kycStatusCache = kycStatusCache;
    }

    /**
     * Registers a new user and creates their profile, address, and settings records.
     *
     * @param request validated registration payload
     * @return summary response with the generated user ID
     * @throws ConflictException if the email address is already registered
     */
    @Transactional
    public UserResponse register(UserRegistrationRequest request) {
        // Guard against duplicate emails before doing any writes.
        boolean exists = dsl.fetchExists(dsl.selectFrom(Users.USERS).where(Users.USERS.EMAIL.eq(request.email())));
        if (exists) {
            throw new ConflictException("User with email " + request.email() + " already exists");
        }

        UUID userId = UUID.randomUUID();
        // Hash the password with BCrypt before storage — plain-text passwords never touch the DB.
        String encodedPassword = passwordEncoder.encode(request.password());

        dsl.insertInto(Users.USERS)
                .set(Users.USERS.ID, userId)
                .set(Users.USERS.EMAIL, request.email())
                .set(Users.USERS.PASSWORD_HASH, encodedPassword)
                .set(Users.USERS.STATUS, "ACTIVE")
                .set(Users.USERS.ROLE, "CUSTOMER")
                .set(Users.USERS.PLAN_TIER, "STANDARD")
                .execute();

        // Profile row holds PII and KYC state. KYC always starts as PENDING —
        // approval is a separate manual/admin step outside the MVP scope.
        UUID profileId = UUID.randomUUID();
        dsl.insertInto(UserProfiles.USER_PROFILES)
                .set(UserProfiles.USER_PROFILES.ID, profileId)
                .set(UserProfiles.USER_PROFILES.USER_ID, userId)
                .set(UserProfiles.USER_PROFILES.FIRST_NAME, request.firstName())
                .set(UserProfiles.USER_PROFILES.LAST_NAME, request.lastName())
                .set(UserProfiles.USER_PROFILES.PHONE_NUMBER, request.phoneNumber())
                .set(UserProfiles.USER_PROFILES.DATE_OF_BIRTH, request.dateOfBirth())
                .set(UserProfiles.USER_PROFILES.KYC_STATUS, "PENDING")
                .set(UserProfiles.USER_PROFILES.LEGAL_ENTITY, "NEOBANK_US")
                .execute();

        dsl.insertInto(UserAddresses.USER_ADDRESSES)
                .set(UserAddresses.USER_ADDRESSES.ID, UUID.randomUUID())
                .set(UserAddresses.USER_ADDRESSES.PROFILE_ID, profileId)
                .set(UserAddresses.USER_ADDRESSES.COUNTRY_CODE, request.countryCode())
                .set(UserAddresses.USER_ADDRESSES.ADDRESS_LINE_1, request.addressLine1())
                .set(UserAddresses.USER_ADDRESSES.CITY, request.city())
                .set(UserAddresses.USER_ADDRESSES.POSTAL_CODE, request.postalCode())
                .set(UserAddresses.USER_ADDRESSES.IS_ACTIVE, true)
                .execute();

        // Settings row is created with sensible defaults; users can update later.
        dsl.insertInto(UserSettings.USER_SETTINGS)
                .set(UserSettings.USER_SETTINGS.USER_ID, userId)
                .set(UserSettings.USER_SETTINGS.LANGUAGE, "en-US")
                .set(UserSettings.USER_SETTINGS.DEFAULT_CURRENCY, "USD")
                .set(UserSettings.USER_SETTINGS.THEME, "SYSTEM")
                .execute();

        return new UserResponse(userId, request.email(), request.firstName(), request.lastName(), "ACTIVE");
    }

    /**
     * Returns the KYC status for the given user, served from Redis cache where possible.
     *
     * <p>Cache strategy:
     * <ol>
     *   <li>Read {@code kyc:{userId}} from Redis</li>
     *   <li>On hit: return cached value immediately</li>
     *   <li>On miss: query {@code user_profiles}, write result back to Redis, return</li>
     * </ol>
     *
     * @param userId the user's UUID
     * @return KYC status response
     * @throws NotFoundException if no profile exists for the given userId
     */
    public KycStatusResponse getKycStatus(UUID userId) {
        // Check Redis first — avoids a DB query on repeated calls (e.g., from ledger-service KYC gate)
        return kycStatusCache.get(userId)
                .map(status -> new KycStatusResponse(userId, KycStatus.valueOf(status)))
                .orElseGet(() -> {
                    String kycStatus = dsl.select(UserProfiles.USER_PROFILES.KYC_STATUS)
                            .from(UserProfiles.USER_PROFILES)
                            .where(UserProfiles.USER_PROFILES.USER_ID.eq(userId))
                            .fetchOne(UserProfiles.USER_PROFILES.KYC_STATUS);


                    if (kycStatus == null) {
                        throw new NotFoundException("User profile not found for userId: " + userId);
                    }
                    // Populate cache for subsequent calls within the TTL window.
                    kycStatusCache.put(userId, kycStatus);
                    return new KycStatusResponse(userId, KycStatus.valueOf(kycStatus));
                });
    }
}
