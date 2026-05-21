package com.neobank.userservice.controller;

import com.neobank.userservice.dto.UserRegistrationRequest;
import com.neobank.userservice.dto.UserResponse;
import com.neobank.userservice.jooq.tables.Users;
import com.neobank.userservice.service.UserService;
import jakarta.validation.Valid;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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

    @GetMapping
    public List<Map<String, Object>> getUsers() {
        return dsl.selectFrom(Users.USERS)
                .fetchMaps();
    }
}
