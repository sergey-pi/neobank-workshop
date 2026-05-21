package com.neobank.userservice.service;

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

@Service
public class UserService {

    private final DSLContext dsl;
    private final PasswordEncoder passwordEncoder;

    public UserService(DSLContext dsl, PasswordEncoder passwordEncoder) {
        this.dsl = dsl;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(UserRegistrationRequest request) {
        // 1. Check if user already exists
        boolean exists = dsl.fetchExists(dsl.selectFrom(Users.USERS).where(Users.USERS.EMAIL.eq(request.email())));
        if (exists) {
            throw new RuntimeException("User with email " + request.email() + " already exists");
        }

        UUID userId = UUID.randomUUID();
        String encodedPassword = passwordEncoder.encode(request.password());

        // 2. Create User record
        dsl.insertInto(Users.USERS)
                .set(Users.USERS.ID, userId)
                .set(Users.USERS.EMAIL, request.email())
                .set(Users.USERS.PASSWORD_HASH, encodedPassword)
                .set(Users.USERS.STATUS, "ACTIVE")
                .set(Users.USERS.ROLE, "CUSTOMER")
                .set(Users.USERS.PLAN_TIER, "STANDARD")
                .execute();

        // 3. Create Profile record
        UUID profileId = UUID.randomUUID();
        dsl.insertInto(UserProfiles.USER_PROFILES)
                .set(UserProfiles.USER_PROFILES.ID, profileId)
                .set(UserProfiles.USER_PROFILES.USER_ID, userId)
                .set(UserProfiles.USER_PROFILES.FIRST_NAME, request.firstName())
                .set(UserProfiles.USER_PROFILES.LAST_NAME, request.lastName())
                .set(UserProfiles.USER_PROFILES.PHONE_NUMBER, request.phoneNumber())
                .set(UserProfiles.USER_PROFILES.DATE_OF_BIRTH, request.dateOfBirth())
                .set(UserProfiles.USER_PROFILES.KYC_STATUS, "PENDING")
                .set(UserProfiles.USER_PROFILES.LEGAL_ENTITY, "NEOBANK_US") // Default for MVP
                .execute();

        // 4. Create Address record
        dsl.insertInto(UserAddresses.USER_ADDRESSES)
                .set(UserAddresses.USER_ADDRESSES.ID, UUID.randomUUID())
                .set(UserAddresses.USER_ADDRESSES.PROFILE_ID, profileId)
                .set(UserAddresses.USER_ADDRESSES.COUNTRY_CODE, request.countryCode())
                .set(UserAddresses.USER_ADDRESSES.ADDRESS_LINE_1, request.addressLine1())
                .set(UserAddresses.USER_ADDRESSES.CITY, request.city())
                .set(UserAddresses.USER_ADDRESSES.POSTAL_CODE, request.postalCode())
                .set(UserAddresses.USER_ADDRESSES.IS_ACTIVE, true)
                .execute();

        // 5. Create Settings record
        dsl.insertInto(UserSettings.USER_SETTINGS)
                .set(UserSettings.USER_SETTINGS.USER_ID, userId)
                .set(UserSettings.USER_SETTINGS.LANGUAGE, "en-US")
                .set(UserSettings.USER_SETTINGS.DEFAULT_CURRENCY, "USD")
                .set(UserSettings.USER_SETTINGS.THEME, "SYSTEM")
                .execute();

        return new UserResponse(userId, request.email(), request.firstName(), request.lastName(), "ACTIVE");
    }
}
