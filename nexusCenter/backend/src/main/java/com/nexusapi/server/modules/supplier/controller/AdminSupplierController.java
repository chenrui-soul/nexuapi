package com.nexusapi.server.modules.supplier.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.supplier.dto.AdminSupplierRequest;
import com.nexusapi.server.modules.supplier.service.AdminSupplierService;
import com.nexusapi.server.modules.supplier.vo.AdminSupplierDetailResponse;
import com.nexusapi.server.modules.supplier.vo.AdminSupplierResponse;
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

/** 管理员供应商列表、创建和乐观锁更新入口。 */
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/suppliers")
public class AdminSupplierController {
    private final AdminSupplierService service;

    public AdminSupplierController(AdminSupplierService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<PageResponse<AdminSupplierResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status,
            @RequestParam(name = "health_status", required = false) String healthStatus
    ) {
        return ApiResponse.ok(service.list(page, pageSize, query, status, healthStatus));
    }

    /** 返回供应商、渠道、模型映射以及最近 30 天经营质量的安全聚合详情。 */
    @GetMapping("/{id}/detail")
    ApiResponse<AdminSupplierDetailResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminSupplierResponse>> create(
            Authentication authentication,
            @Valid @RequestBody AdminSupplierRequest body,
            HttpServletRequest request
    ) {
        AdminSupplierResponse created = service.create(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    ApiResponse<AdminSupplierResponse> update(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody AdminSupplierRequest body,
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
