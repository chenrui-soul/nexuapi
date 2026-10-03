package com.nexusapi.server.modules.apikey.security;

import com.nexusapi.server.common.security.JsonApiKeyAuthenticationEntryPoint;
import com.nexusapi.server.modules.apikey.service.ApiKeyAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * `/v1/**` 的无状态 Bearer API 令牌认证过滤器。
 *
 * <p>只接受一个 Authorization Header，认证失败统一交给 OpenAI 风格入口返回 401，
 * 不把具体失败原因暴露给调用方。</p>
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER_SCHEME = "Bearer ";
    private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(
            new SimpleGrantedAuthority("ROLE_API_KEY")
    );

    private final ApiKeyAuthenticationService authenticationService;
    private final JsonApiKeyAuthenticationEntryPoint authenticationEntryPoint;

    public ApiKeyAuthenticationFilter(
            ApiKeyAuthenticationService authenticationService,
            JsonApiKeyAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.authenticationService = authenticationService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String secret = readBearerSecret(request);
        NexusApiKeyPrincipal principal = authenticationService.authenticate(secret);
        if (principal == null) {
            reject(request, response);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                AUTHORITIES
        ));
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    private String readBearerSecret(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (values.size() != 1) {
            return null;
        }
        String value = values.get(0);
        if (value == null
                || value.length() <= BEARER_SCHEME.length()
                || !value.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
            return null;
        }
        return value.substring(BEARER_SCHEME.length());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(
                request,
                response,
                new BadCredentialsException("API key authentication failed")
        );
    }
}
