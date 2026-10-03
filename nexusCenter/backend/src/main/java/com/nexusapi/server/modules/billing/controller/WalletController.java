package com.nexusapi.server.modules.billing.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.modules.billing.vo.BillingLedgerItemResponse;
import com.nexusapi.server.modules.billing.vo.WalletBalanceResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户控制台的钱包读取入口。
 *
 * <p>写资金接口不向普通用户暴露；支付、Gateway 和管理模块必须调用 BillingService。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {
    private final BillingService service;

    public WalletController(BillingService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<WalletBalanceResponse> balance(Authentication authentication) {
        return ApiResponse.ok(service.getBalance(principal(authentication).userId()));
    }

    @GetMapping("/ledger")
    ApiResponse<PageResponse<BillingLedgerItemResponse>> ledger(
            Authentication authentication,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.ok(service.listLedger(principal(authentication).userId(), page, pageSize));
    }

    /** userId 只来自已认证 Session，防止跨用户查询资金数据。 */
    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}

