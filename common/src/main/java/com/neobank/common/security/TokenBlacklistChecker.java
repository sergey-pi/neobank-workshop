package com.neobank.common.security;

@FunctionalInterface
public interface TokenBlacklistChecker {

    boolean isBlacklisted(String jti);
}
