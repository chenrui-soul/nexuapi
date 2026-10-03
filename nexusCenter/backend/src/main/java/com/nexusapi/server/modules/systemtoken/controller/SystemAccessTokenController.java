package com.nexusapi.server.modules.systemtoken.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.systemtoken.dto.SystemAccessTokenCreateRequest;
import com.nexusapi.server.modules.systemtoken.dto.SystemAccessTokenStatusRequest;
import com.nexusapi.server.modules.systemtoken.service.SystemAccessTokenService;
import com.nexusapi.server.modules.systemtoken.vo.SystemAccessScopeResponse;
import com.nexusapi.server.modules.systemtoken.vo.SystemAccessTokenCreatedResponse;
import com.nexusapi.server.modules.systemtoken.vo.SystemAccessTokenItemResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 登录用户管理自己系统访问令牌的 Cookie Session + CSRF 入口。 */
@RestController
@RequestMapping("/api/v1/system-access-tokens")
public class SystemAccessTokenController {
    private final SystemAccessTokenService service;

    public SystemAccessTokenController(SystemAccessTokenService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<SystemAccessTokenItemResponse>> list(Authentication authentication) {
        return ApiResponse.ok(service.list(principal(authentication).userId()));
    }

    @GetMapping("/scopes")
    ApiResponse<List<SystemAccessScopeResponse>> scopes() { return ApiResponse.ok(service.scopes()); }

    @PostMapping
    ResponseEntity<ApiResponse<SystemAccessTokenCreatedResponse>> create(
            Authentication authentication,
            @Valid @RequestBody SystemAccessTokenCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(principal(authentication).userId(), request)));
    }

    @PutMapping("/{id}/status")
    ApiResponse<SystemAccessTokenItemResponse> status(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody SystemAccessTokenStatusRequest request
    ) {
        return ApiResponse.ok(service.changeStatus(principal(authentication).userId(), id, request));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Map<String, Boolean>> revoke(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(Map.of("revoked", service.revoke(principal(authentication).userId(), id)));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
