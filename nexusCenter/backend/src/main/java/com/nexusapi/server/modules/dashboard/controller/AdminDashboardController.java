package com.nexusapi.server.modules.dashboard.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.dashboard.service.AdminDashboardService;
import com.nexusapi.server.modules.dashboard.vo.AdminDashboardOverviewResponse;
import com.nexusapi.server.modules.dashboard.vo.AdminResourceOverviewResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Wave 8 管理端经营分析入口，只暴露脱敏聚合结果。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {
    private final AdminDashboardService service;

    public AdminDashboardController(AdminDashboardService service) {
        this.service = service;
    }

    /** 返回运营总览资源真实统计，不使用管理员列表的分页结果。 */
    @GetMapping("/resources")
    ApiResponse<AdminResourceOverviewResponse> resources() {
        return ApiResponse.ok(service.resources());
    }

    @GetMapping("/overview")
    ApiResponse<AdminDashboardOverviewResponse> overview(
            @RequestParam(defaultValue = "7d") String preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "supplier_id", required = false) String supplierId
    ) {
        return ApiResponse.ok(service.overview(preset, from, to, supplierId));
    }
}
