package com.nexusapi.server.modules.billing.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.billing.dto.RechargeOrderCreateRequest;
import com.nexusapi.server.modules.billing.service.RechargeOrderService;
import com.nexusapi.server.modules.billing.vo.RechargeOrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallet/recharge-orders")
public class RechargeOrderController {
    private final RechargeOrderService service;

    public RechargeOrderController(RechargeOrderService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<RechargeOrderResponse>> create(
            Authentication authentication,
            @Valid @RequestBody RechargeOrderCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                service.create(principal(authentication).userId(), request, idempotencyKey)
        ));
    }

    @GetMapping("/{id}")
    ApiResponse<RechargeOrderResponse> get(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(service.get(principal(authentication).userId(), id));
    }

    @PostMapping("/{id}/mock-pay")
    ApiResponse<RechargeOrderResponse> mockPay(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(service.mockPay(principal(authentication).userId(), id));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
