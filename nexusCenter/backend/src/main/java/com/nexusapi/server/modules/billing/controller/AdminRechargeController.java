package com.nexusapi.server.modules.billing.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.billing.service.AdminRechargeService;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.modules.billing.vo.WalletBalanceResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/users/{id}")
public class AdminRechargeController {
    private final AdminRechargeService service;
    private final BillingService billing;
    public AdminRechargeController(AdminRechargeService service, BillingService billing) {
        this.service = service;
        this.billing = billing;
    }
    @GetMapping("/wallet")
    public ApiResponse<WalletBalanceResponse> wallet(@PathVariable UUID id) { return ApiResponse.ok(billing.getBalance(id)); }
    @PostMapping("/recharge")
    public ApiResponse<AdminRechargeService.Receipt> recharge(@PathVariable UUID id,
            @Valid @RequestBody AdminRechargeService.Input input,
            @AuthenticationPrincipal NexusUserPrincipal actor, HttpServletRequest request) {
        return ApiResponse.ok(service.recharge(id, actor.userId(), input, ClientRequestMetadata.from(request)));
    }
}
