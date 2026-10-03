package com.nexusapi.server.modules.model.pricing.time.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.model.pricing.time.dto.AdminTimePricingDeleteRequest;
import com.nexusapi.server.modules.model.pricing.time.dto.AdminTimePricingRuleRequest;
import com.nexusapi.server.modules.model.pricing.time.dto.AdminTimePricingStatusRequest;
import com.nexusapi.server.modules.model.pricing.time.service.AdminTimePricingService;
import com.nexusapi.server.modules.model.pricing.time.vo.AdminTimePricingRuleResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.UUID;

/** 管理员时段计费设置入口；所有写操作均受 Session、管理员角色和 CSRF 保护。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/billing/time-rules")
public class AdminTimePricingController {
    private final AdminTimePricingService service;

    public AdminTimePricingController(AdminTimePricingService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<AdminTimePricingRuleResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminTimePricingRuleResponse>> create(
            Authentication authentication,
            @Valid @RequestBody AdminTimePricingRuleRequest body,
            HttpServletRequest request
    ) {
        AdminTimePricingRuleResponse created = service.create(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{ruleId}")
    ApiResponse<AdminTimePricingRuleResponse> update(
            Authentication authentication,
            @PathVariable UUID ruleId,
            @Valid @RequestBody AdminTimePricingRuleRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.update(
                principal(authentication).userId(), ruleId, body, ClientRequestMetadata.from(request)
        ));
    }

    @PutMapping("/{ruleId}/status")
    ApiResponse<AdminTimePricingRuleResponse> updateStatus(
            Authentication authentication,
            @PathVariable UUID ruleId,
            @Valid @RequestBody AdminTimePricingStatusRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.updateStatus(
                principal(authentication).userId(), ruleId, body, ClientRequestMetadata.from(request)
        ));
    }

    @DeleteMapping("/{ruleId}")
    ApiResponse<Void> delete(
            Authentication authentication,
            @PathVariable UUID ruleId,
            @Valid @RequestBody AdminTimePricingDeleteRequest body,
            HttpServletRequest request
    ) {
        service.delete(principal(authentication).userId(), ruleId, body, ClientRequestMetadata.from(request));
        return ApiResponse.ok(null);
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}

