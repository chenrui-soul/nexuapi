package com.nexusapi.server.modules.auth.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.dto.LoginRequest;
import com.nexusapi.server.modules.auth.dto.ChangePasswordRequest;
import com.nexusapi.server.modules.auth.dto.PasswordForgotRequest;
import com.nexusapi.server.modules.auth.dto.PasswordResetRequest;
import com.nexusapi.server.modules.auth.dto.RegisterRequest;
import com.nexusapi.server.modules.auth.enums.CaptchaScene;
import com.nexusapi.server.modules.auth.security.AuthSessionService;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.AuthService;
import com.nexusapi.server.modules.auth.service.AuthenticatedUser;
import com.nexusapi.server.modules.auth.service.CaptchaService;
import com.nexusapi.server.modules.auth.service.PasswordResetService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.auth.vo.AuthSessionResponse;
import com.nexusapi.server.modules.auth.vo.AuthUserResponse;
import com.nexusapi.server.modules.auth.vo.CaptchaChallenge;
import com.nexusapi.server.modules.auth.vo.AccountSecurityResponse;
import com.nexusapi.server.modules.auth.vo.PasswordResetChallenge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final CaptchaService captchaService;
    private final AuthService authService;
    private final AuthSessionService sessionService;
    private final PasswordResetService passwordResetService;

    public AuthController(CaptchaService captchaService, AuthService authService, AuthSessionService sessionService,
                          PasswordResetService passwordResetService) {
        this.captchaService = captchaService;
        this.authService = authService;
        this.sessionService = sessionService;
        this.passwordResetService = passwordResetService;
    }

    @GetMapping("/captcha")
    ApiResponse<CaptchaChallenge> captcha(
            @RequestParam(defaultValue = "login") String scene,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(captchaService.issue(CaptchaScene.parse(scene), request.getRemoteAddr()));
    }

    @PostMapping("/register")
    ResponseEntity<ApiResponse<AuthSessionResponse>> register(
            @Valid @RequestBody RegisterRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedUser user = authService.register(body, ClientRequestMetadata.from(request));
        long expiresIn = sessionService.start(request, user.id(), false);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(new AuthSessionResponse(toResponse(user), expiresIn)));
    }

    @PostMapping("/login")
    ApiResponse<AuthSessionResponse> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedUser user = authService.login(body, ClientRequestMetadata.from(request));
        long expiresIn = sessionService.start(request, user.id(), body.remember());
        return ApiResponse.ok(new AuthSessionResponse(toResponse(user), expiresIn));
    }

    @GetMapping("/me")
    ApiResponse<AuthUserResponse> me(Authentication authentication) {
        NexusUserPrincipal principal = principal(authentication);
        return ApiResponse.ok(toResponse(authService.currentUser(principal.userId())));
    }

    @PostMapping("/logout")
    ApiResponse<Map<String, Boolean>> logout(Authentication authentication, HttpServletRequest request) {
        NexusUserPrincipal principal = principal(authentication);
        authService.recordLogout(principal.userId(), ClientRequestMetadata.from(request));
        sessionService.invalidate(request);
        SecurityContextHolder.clearContext();
        return ApiResponse.ok(Map.of("logged_out", true));
    }

    @PostMapping("/password/forgot")
    ApiResponse<PasswordResetChallenge> forgotPassword(@Valid @RequestBody PasswordForgotRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(passwordResetService.issue(body, ClientRequestMetadata.from(request)));
    }

    @PostMapping("/password/reset")
    ApiResponse<Map<String, Boolean>> resetPassword(@Valid @RequestBody PasswordResetRequest body,
                                                     HttpServletRequest request) {
        passwordResetService.reset(body, ClientRequestMetadata.from(request));
        return ApiResponse.ok(Map.of("password_reset", true));
    }

    @GetMapping("/security")
    ApiResponse<AccountSecurityResponse> security(Authentication authentication) {
        return ApiResponse.ok(authService.security(principal(authentication).userId()));
    }

    @PostMapping("/password/change")
    ApiResponse<Map<String, Object>> changePassword(Authentication authentication,
                                                    @Valid @RequestBody ChangePasswordRequest body,
                                                    HttpServletRequest request) {
        UUID userId = principal(authentication).userId();
        int revoked = authService.changePassword(userId, body, request, ClientRequestMetadata.from(request));
        return ApiResponse.ok(Map.of("password_changed", true, "revoked_sessions", revoked));
    }

    @PostMapping("/sessions/revoke-others")
    ApiResponse<Map<String, Integer>> revokeOtherSessions(Authentication authentication, HttpServletRequest request) {
        UUID userId = principal(authentication).userId();
        return ApiResponse.ok(Map.of("revoked_count", sessionService.revokeOthers(userId, request)));
    }

    @GetMapping("/csrf")
    ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.ok(Map.of("header", token.getHeaderName(), "token", token.getToken()));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }

    private AuthUserResponse toResponse(AuthenticatedUser user) {
        return new AuthUserResponse(user.id(), user.name(), user.email(), user.status(), user.roles(), user.createdAt());
    }
}
