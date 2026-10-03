package com.nexusapi.server.modules.subscription.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.subscription.dto.AdminSubscriptionPlanArchiveRequest;
import com.nexusapi.server.modules.subscription.dto.AdminSubscriptionPlanRequest;
import com.nexusapi.server.modules.subscription.service.AdminSubscriptionPlanService;
import com.nexusapi.server.modules.subscription.vo.AdminSubscriptionPlanOptionsResponse;
import com.nexusapi.server.modules.subscription.vo.AdminSubscriptionPlanResponse;
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

/** 管理员订阅套餐配置入口；所有写操作继续受 Session、管理员角色和 CSRF 保护。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/subscription-plans")
public class AdminSubscriptionPlanController {
    private final AdminSubscriptionPlanService service;

    public AdminSubscriptionPlanController(AdminSubscriptionPlanService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<AdminSubscriptionPlanResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/options")
    ApiResponse<AdminSubscriptionPlanOptionsResponse> options() {
        return ApiResponse.ok(service.options());
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminSubscriptionPlanResponse>> create(
            Authentication authentication,
            @Valid @RequestBody AdminSubscriptionPlanRequest body,
            HttpServletRequest request
    ) {
        AdminSubscriptionPlanResponse created = service.create(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{planId}")
    ApiResponse<AdminSubscriptionPlanResponse> update(
            Authentication authentication,
            @PathVariable UUID planId,
            @Valid @RequestBody AdminSubscriptionPlanRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.update(
                principal(authentication).userId(), planId, body, ClientRequestMetadata.from(request)
        ));
    }

    /** DELETE 表达管理员意图，服务层实际执行可恢复的软归档。 */
    @DeleteMapping("/{planId}")
    ApiResponse<AdminSubscriptionPlanResponse> archive(
            Authentication authentication,
            @PathVariable UUID planId,
            @Valid @RequestBody AdminSubscriptionPlanArchiveRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.archive(
                principal(authentication).userId(), planId, body, ClientRequestMetadata.from(request)
        ));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
