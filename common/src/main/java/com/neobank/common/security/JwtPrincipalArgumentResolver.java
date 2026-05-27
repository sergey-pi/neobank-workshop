package com.neobank.common.security;

import com.neobank.common.exception.UnauthorizedException;
import com.neobank.common.filter.RequestAttributes;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link JwtPrincipal} parameters annotated with {@link AuthenticatedPrincipal}.
 * Register via {@code WebMvcConfigurer#addArgumentResolvers} in each service.
 */
public class JwtPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthenticatedPrincipal.class)
                && JwtPrincipal.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        JwtPrincipal principal = RequestAttributes.getPrincipal(
                webRequest.getNativeRequest(jakarta.servlet.http.HttpServletRequest.class));
        if (principal == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return principal;
    }
}
