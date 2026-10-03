package com.nexusapi.server.modules.admin.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.admin.vo.AdminAuditLogResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员只读审计日志查询入口。 */
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/audit-logs")
public class AdminAuditController {
    private final AdminAuditService service;

    public AdminAuditController(AdminAuditService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<PageResponse<AdminAuditLogResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(name = "resource_type", required = false) @Size(max = 80) String resourceType,
            @RequestParam(name = "actor_type", required = false) @Size(max = 24) String actorType
    ) {
        return ApiResponse.ok(service.list(page, pageSize, query, resourceType, actorType));
    }
}
