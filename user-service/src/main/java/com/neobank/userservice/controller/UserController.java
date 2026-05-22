package com.neobank.userservice.controller;

import com.neobank.userservice.dto.KycStatusResponse;
import com.neobank.userservice.dto.LoginRequest;
import com.neobank.userservice.dto.LoginResponse;
import com.neobank.userservice.dto.UserRegistrationRequest;
import com.neobank.userservice.dto.UserResponse;
import com.neobank.userservice.jooq.tables.Users;
import com.neobank.userservice.service.UserService;
import jakarta.validation.Valid;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final DSLContext dsl;
    private final UserService userService;

    public UserController(DSLContext dsl, UserService userService) {
        this.dsl = dsl;
        this.userService = userService;
    }

    @PostMapping("/register")
    public UserResponse registerUser(@Valid @RequestBody UserRegistrationRequest request) {
        return userService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return userService.login(request);
    }

    @GetMapping
    public List<Map<String, Object>> getUsers() {
        return dsl.selectFrom(Users.USERS)
                .fetchMaps();
    }

    @GetMapping("/{userId}/kyc-status")
    public KycStatusResponse getKycStatus(@PathVariable UUID userId) {
        return userService.getKycStatus(userId);
    }
}
