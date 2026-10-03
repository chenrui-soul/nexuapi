package com.nexusapi.server.modules.systemtoken.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.modules.billing.vo.BillingLedgerItemResponse;
import com.nexusapi.server.modules.billing.vo.WalletBalanceResponse;
import com.nexusapi.server.modules.dashboard.service.UserAnalyticsService;
import com.nexusapi.server.modules.dashboard.vo.UserDashboardOverviewResponse;
import com.nexusapi.server.modules.requestlog.service.UserRequestLogService;
import com.nexusapi.server.modules.requestlog.vo.UserRequestLogResponse;
import com.nexusapi.server.modules.systemtoken.security.NexusSystemAccessPrincipal;
import com.nexusapi.server.modules.systemtoken.service.SystemAccessTokenAuthenticationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** 系统访问令牌可调用的只读账户数据接口，不接受 Cookie Session 或用户 API 令牌。 */
@Validated
@RestController
@RequestMapping("/api/v1/system-access")
public class SystemAccessDataController {
    private final UserAnalyticsService analyticsService;
    private final BillingService billingService;
    private final UserRequestLogService requestLogService;
    private final SystemAccessTokenAuthenticationService tokenService;

    public SystemAccessDataController(
            UserAnalyticsService analyticsService,
            BillingService billingService,
            UserRequestLogService requestLogService,
            SystemAccessTokenAuthenticationService tokenService
    ) {
        this.analyticsService = analyticsService;
        this.billingService = billingService;
        this.requestLogService = requestLogService;
        this.tokenService = tokenService;
    }

    @GetMapping("/me")
    ApiResponse<Map<String, Object>> me(Authentication authentication) {
        NexusSystemAccessPrincipal principal = principal(authentication);
        touch(principal);
        return ApiResponse.ok(Map.of(
                "token_id", principal.tokenId(),
                "masked_token", principal.maskedToken(),
                "scopes", principal.scopes()
        ));
    }

    @GetMapping("/dashboard/overview")
    ApiResponse<UserDashboardOverviewResponse> dashboard(
            Authentication authentication,
            @RequestParam(defaultValue = "today") String preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        NexusSystemAccessPrincipal principal = require(authentication, "dashboard:read");
        UserDashboardOverviewResponse result = analyticsService.dashboard(principal.userId(), preset, from, to);
        touch(principal);
        return ApiResponse.ok(result);
    }

    @GetMapping("/wallet")
    ApiResponse<WalletBalanceResponse> wallet(Authentication authentication) {
        NexusSystemAccessPrincipal principal = require(authentication, "wallet:read");
        WalletBalanceResponse result = billingService.getBalance(principal.userId());
        touch(principal);
        return ApiResponse.ok(result);
    }

    @GetMapping("/wallet/ledger")
    ApiResponse<PageResponse<BillingLedgerItemResponse>> ledger(
            Authentication authentication,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        NexusSystemAccessPrincipal principal = require(authentication, "wallet:read");
        PageResponse<BillingLedgerItemResponse> result = billingService.listLedger(principal.userId(), page, pageSize);
        touch(principal);
        return ApiResponse.ok(result);
    }

    @GetMapping("/request-logs")
    ApiResponse<PageResponse<UserRequestLogResponse>> logs(
            Authentication authentication,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @Size(max = 160) String model,
            @RequestParam(defaultValue = "24h") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        NexusSystemAccessPrincipal principal = require(authentication, "logs:read");
        PageResponse<UserRequestLogResponse> result = requestLogService.list(
                principal.userId(), page, pageSize, query, status, model, period, from, to
        );
        touch(principal);
        return ApiResponse.ok(result);
    }

    @GetMapping("/request-logs/{requestId}")
    ApiResponse<UserRequestLogResponse> log(
            Authentication authentication,
            @PathVariable @Size(max = 80) String requestId
    ) {
        NexusSystemAccessPrincipal principal = require(authentication, "logs:read");
        UserRequestLogResponse result = requestLogService.get(principal.userId(), requestId);
        touch(principal);
        return ApiResponse.ok(result);
    }

    private NexusSystemAccessPrincipal require(Authentication authentication, String scope) {
        NexusSystemAccessPrincipal principal = principal(authentication);
        if (!principal.hasScope(scope)) throw new BusinessException(ErrorCode.SYSTEM_ACCESS_TOKEN_SCOPE_DENIED);
        return principal;
    }

    private NexusSystemAccessPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof NexusSystemAccessPrincipal principal)) {
            throw new BusinessException(ErrorCode.SYSTEM_ACCESS_TOKEN_INVALID);
        }
        return principal;
    }

    private void touch(NexusSystemAccessPrincipal principal) {
        tokenService.recordSuccessfulUse(principal.tokenId());
    }
}
