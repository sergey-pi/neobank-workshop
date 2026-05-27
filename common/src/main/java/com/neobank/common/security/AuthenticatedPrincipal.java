package com.neobank.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the authenticated {@link JwtPrincipal} to a controller method parameter.
 * Resolved by {@link JwtPrincipalArgumentResolver}; eliminates the need to inject
 * {@code HttpServletRequest} just to read the request attribute.
 *
 * <pre>{@code
 * @GetMapping
 * public List<AccountResponse> getAccounts(@AuthenticatedPrincipal JwtPrincipal principal) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedPrincipal {
}
