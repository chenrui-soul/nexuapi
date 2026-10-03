package com.nexusapi.server.modules.subscription.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.subscription.service.SubscriptionService;
import com.nexusapi.server.modules.subscription.dto.SubscriptionCancelRequest;
import com.nexusapi.server.modules.subscription.dto.SubscriptionOrderCreateRequest;
import com.nexusapi.server.modules.subscription.vo.SubscriptionOrderResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import com.nexusapi.server.modules.subscription.vo.SubscriptionCancellationResponse;
import com.nexusapi.server.modules.subscription.vo.SubscriptionOverviewResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 用户侧订阅计划入口；写操作受 Cookie Session 与 CSRF 保护。 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {
    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) { this.service = service; }

    @GetMapping
    ApiResponse<SubscriptionOverviewResponse> overview(Authentication authentication) {
        return ApiResponse.ok(service.overview(principal(authentication).userId()));
    }

    @PostMapping("/plans/{planId}/mock-activate")
    ApiResponse<SubscriptionOverviewResponse> activateMock(
            Authentication authentication,
            @PathVariable UUID planId
    ) {
        return ApiResponse.ok(service.activateMock(principal(authentication).userId(), planId));
    }

    @PostMapping("/plans/{planId}/orders")
    ApiResponse<SubscriptionOrderResponse> createOrder(
            Authentication authentication,
            @PathVariable UUID planId,
            @Valid @RequestBody SubscriptionOrderCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.createOrder(principal(authentication).userId(), planId, request, idempotencyKey));
    }

    @PostMapping("/current/cancel")
    ApiResponse<SubscriptionCancellationResponse> cancel(
            Authentication authentication,
            @RequestBody SubscriptionCancelRequest request
    ) {
        return ApiResponse.ok(service.cancel(principal(authentication).userId(), request));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
