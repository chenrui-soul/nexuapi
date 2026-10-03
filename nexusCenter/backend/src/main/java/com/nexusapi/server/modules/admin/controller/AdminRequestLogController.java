package com.nexusapi.server.modules.admin.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.modules.admin.service.AdminRequestLogService;
import com.nexusapi.server.modules.admin.vo.AdminRequestLogResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/request-logs")
public class AdminRequestLogController {
    private final AdminRequestLogService service;

    public AdminRequestLogController(AdminRequestLogService service) { this.service = service; }

    @GetMapping
    ApiResponse<PageResponse<AdminRequestLogResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 120) String query,
            @RequestParam(required = false) @Size(max = 16) String status,
            @RequestParam(required = false) @Size(max = 160) String model,
            @RequestParam(required = false) @Size(max = 8) String period
    ) { return ApiResponse.ok(service.list(page, pageSize, query, status, model, period)); }

    @GetMapping("/{requestId}")
    ApiResponse<AdminRequestLogResponse> detail(@PathVariable @Size(max = 80) String requestId) {
        return ApiResponse.ok(service.get(requestId));
    }
}
