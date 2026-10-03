package com.nexusapi.server.modules.dashboard.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.dashboard.service.UserAnalyticsService;
import com.nexusapi.server.modules.dashboard.vo.UserDashboardOverviewResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 用户控制台仪表盘入口，只聚合当前登录用户自己的调用事实。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class UserDashboardController {
    private final UserAnalyticsService service;

    public UserDashboardController(UserAnalyticsService service) {
        this.service = service;
    }

    /** 查询当前用户仪表盘总览。 */
    @GetMapping("/overview")
    ApiResponse<UserDashboardOverviewResponse> overview(
            Authentication authentication,
            @RequestParam(defaultValue = "today") String preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        return ApiResponse.ok(service.dashboard(principal(authentication).userId(), preset, from, to));
    }

    /** userId 只接受已认证 Session 中的服务端主体。 */
    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
