package com.nexusapi.server.modules.gateway.adapter;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 管理员适配器目录入口；目录配置不包含凭证，实际转换仍由后端代码实现。 */
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/adapters")
public class AdminAdapterController {
    private final AdapterCatalogService service;

    public AdminAdapterController(AdapterCatalogService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<PageResponse<AdminAdapterResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status,
            @RequestParam(name = "capability_type", required = false) String capabilityType
    ) {
        return ApiResponse.ok(service.list(page, pageSize, query, status, capabilityType));
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminAdapterResponse>> create(
            Authentication authentication,
            @Valid @RequestBody AdminAdapterRequest body,
            HttpServletRequest request
    ) {
        AdminAdapterResponse created = service.create(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    ApiResponse<AdminAdapterResponse> update(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody AdminAdapterRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.update(
                principal(authentication).userId(), id, body, ClientRequestMetadata.from(request)
        ));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
