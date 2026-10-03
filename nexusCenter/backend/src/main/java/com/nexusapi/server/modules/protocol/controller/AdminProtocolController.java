package com.nexusapi.server.modules.protocol.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.protocol.dto.AdminProtocolRequest;
import com.nexusapi.server.modules.protocol.service.AdminProtocolService;
import com.nexusapi.server.modules.protocol.vo.AdminProtocolResponse;
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

/** 管理员接口文档入口；所有写接口均由 ROLE_ADMIN 和 CSRF 安全链保护。 */
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/api-interfaces")
public class AdminProtocolController {
    private final AdminProtocolService service;

    public AdminProtocolController(AdminProtocolService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<PageResponse<AdminProtocolResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status,
            @RequestParam(name = "capability_type", required = false) String capabilityType
    ) {
        return ApiResponse.ok(service.list(page, pageSize, query, status, capabilityType));
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminProtocolResponse>> create(
            Authentication authentication,
            @Valid @RequestBody AdminProtocolRequest body,
            HttpServletRequest request
    ) {
        AdminProtocolResponse created = service.create(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    ApiResponse<AdminProtocolResponse> update(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody AdminProtocolRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.update(
                principal(authentication).userId(), id, body, ClientRequestMetadata.from(request)
        ));
    }

    /** 用户 ID 只从服务端会话 Principal 获取，浏览器不能伪造管理员身份。 */
    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
