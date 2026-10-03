package com.nexusapi.server.modules.systemtoken.security;

import com.nexusapi.server.common.security.JsonSystemAccessTokenEntryPoint;
import com.nexusapi.server.modules.gateway.support.IpAllowlistMatcher;
import com.nexusapi.server.modules.systemtoken.service.SystemAccessTokenAuthenticationService;
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

/** `/api/v1/system-access/**` 的独立无状态 Bearer 鉴权过滤器。 */
@Component
public class SystemAccessTokenAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER = "Bearer ";
    private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(
            new SimpleGrantedAuthority("ROLE_SYSTEM_ACCESS_TOKEN")
    );
    private final SystemAccessTokenAuthenticationService service;
    private final JsonSystemAccessTokenEntryPoint entryPoint;

    public SystemAccessTokenAuthenticationFilter(
            SystemAccessTokenAuthenticationService service,
            JsonSystemAccessTokenEntryPoint entryPoint
    ) {
        this.service = service;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        NexusSystemAccessPrincipal principal = service.authenticate(readSecret(request));
        if (principal == null || !IpAllowlistMatcher.isAllowed(request.getRemoteAddr(), principal.ipAllowlist())) {
            reject(request, response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, AUTHORITIES));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private String readSecret(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || value.length() <= BEARER.length()
                || !value.regionMatches(true, 0, BEARER, 0, BEARER.length())) return null;
        return value.substring(BEARER.length());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        entryPoint.commence(request, response, new BadCredentialsException("System access token authentication failed"));
    }
}
