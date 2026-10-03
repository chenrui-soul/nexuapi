package com.nexusapi.server.modules.model.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.model.dto.AdminModelBatchStatusRequest;
import com.nexusapi.server.modules.model.dto.AdminModelRequest;
import com.nexusapi.server.modules.model.dto.ModelSyncSettingsRequest;
import com.nexusapi.server.modules.model.service.AdminModelService;
import com.nexusapi.server.modules.model.service.ModelSyncService;
import com.nexusapi.server.modules.model.vo.AdminModelBatchStatusResponse;
import com.nexusapi.server.modules.model.vo.AdminModelResponse;
import com.nexusapi.server.modules.model.vo.ModelSyncRunResponse;
import com.nexusapi.server.modules.model.vo.ModelSyncStatusResponse;
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

/** 管理员模型目录和售价配置入口。 */
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/models")
public class AdminModelController {
    private final AdminModelService service;
    private final ModelSyncService syncService;

    public AdminModelController(AdminModelService service, ModelSyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }

    /** 查询自动同步开关、周期、下次执行时间和最近一次脱敏结果。 */
    @GetMapping("/sync")
    ApiResponse<ModelSyncStatusResponse> syncStatus() {
        return ApiResponse.ok(syncService.status());
    }

    /** 动态开启、关闭或调整同步周期；更新采用乐观锁避免管理员互相覆盖。 */
    @PutMapping("/sync")
    ApiResponse<ModelSyncStatusResponse> updateSyncSettings(
            Authentication authentication,
            @Valid @RequestBody ModelSyncSettingsRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(syncService.updateSettings(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        ));
    }

    /** 立即执行一次完整分页同步；自动同步关闭时也允许管理员手动运行。 */
    @PostMapping("/sync/run")
    ApiResponse<ModelSyncRunResponse> runSync(
            Authentication authentication,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(syncService.runManual(
                principal(authentication).userId(), ClientRequestMetadata.from(request)
        ));
    }

    @GetMapping
    ApiResponse<PageResponse<AdminModelResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status,
            @RequestParam(name = "capability_type", required = false) String capabilityType,
            @RequestParam(required = false) @Size(max = 80) String provider,
            @RequestParam(name = "service_group", required = false) @Size(max = 120) String serviceGroup
    ) {
        return ApiResponse.ok(service.list(page, pageSize, query, status, capabilityType, provider, serviceGroup));
    }

    /** 批量启用或停用模型；管理员权限和 CSRF 由控制器安全链统一保护。 */
    @PostMapping("/batch-status")
    ApiResponse<AdminModelBatchStatusResponse> batchStatus(
            Authentication authentication,
            @Valid @RequestBody AdminModelBatchStatusRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.batchStatus(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        ));
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminModelResponse>> create(
            Authentication authentication,
            @Valid @RequestBody AdminModelRequest body,
            HttpServletRequest request
    ) {
        AdminModelResponse created = service.create(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    ApiResponse<AdminModelResponse> update(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody AdminModelRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.update(
                principal(authentication).userId(), id, body, ClientRequestMetadata.from(request)
        ));
    }

    /** userId 只取自受 Spring Security 保护的 Session Principal。 */
    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
