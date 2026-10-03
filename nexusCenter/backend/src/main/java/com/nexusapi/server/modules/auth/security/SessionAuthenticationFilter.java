package com.nexusapi.server.modules.auth.security;

import com.nexusapi.server.modules.auth.service.AuthService;
import com.nexusapi.server.modules.auth.service.SessionPrincipalData;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService authService;

    public SessionAuthenticationFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object value = session.getAttribute(AuthSessionService.SESSION_USER_ID);
            if (value instanceof String userIdValue) {
                authenticateSession(session, userIdValue);
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateSession(HttpSession session, String userIdValue) {
        try {
            SessionPrincipalData data = authService.loadSessionPrincipal(UUID.fromString(userIdValue));
            if (data == null || !data.active()) {
                session.invalidate();
                return;
            }
            NexusUserPrincipal principal = new NexusUserPrincipal(data.id(), data.displayName(), data.roles());
            var authorities = data.roles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities)
            );
        } catch (IllegalArgumentException exception) {
            session.invalidate();
        }
    }
}
