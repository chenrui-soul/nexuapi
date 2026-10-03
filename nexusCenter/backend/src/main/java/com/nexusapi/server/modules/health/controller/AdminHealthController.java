package com.nexusapi.server.modules.health.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.health.service.AdminHealthService;
import com.nexusapi.server.modules.health.vo.AdminChannelHealthResponse;
import com.nexusapi.server.modules.health.vo.AdminGroupHealthResponse;
import com.nexusapi.server.modules.health.vo.AdminHealthAlertResponse;
import com.nexusapi.server.modules.health.vo.AdminHealthCheckResponse;
import com.nexusapi.server.modules.health.vo.AdminManualProbeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Wave 7A 管理端健康状态、历史、告警与手动探测入口。 */
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/health")
public class AdminHealthController {
    private final AdminHealthService service;

    public AdminHealthController(AdminHealthService service) {
        this.service = service;
    }

    @GetMapping("/channels")
    ApiResponse<PageResponse<AdminChannelHealthResponse>> listChannels(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.listChannels(page, pageSize, query, status));
    }

    @GetMapping("/groups")
    ApiResponse<PageResponse<AdminGroupHealthResponse>> listGroups(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(name = "health_status", required = false) String healthStatus
    ) {
        return ApiResponse.ok(service.listGroups(page, pageSize, query, healthStatus));
    }

    @GetMapping("/checks")
    ApiResponse<PageResponse<AdminHealthCheckResponse>> listChecks(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(name = "target_type", required = false) String targetType,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.listChecks(page, pageSize, targetType, status));
    }

    @GetMapping("/alerts")
    ApiResponse<PageResponse<AdminHealthAlertResponse>> listAlerts(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.listAlerts(page, pageSize, status));
    }

    @PostMapping("/channels/{id}/probe")
    ApiResponse<AdminManualProbeResponse> probeChannel(
            Authentication authentication,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.probeChannel(
                principal(authentication).userId(), id, ClientRequestMetadata.from(request)
        ));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
