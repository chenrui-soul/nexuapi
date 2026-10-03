package com.nexusapi.server.modules.finance.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.finance.service.MultiCurrencyFinanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理端多币种基础维护；不包含正式支付渠道和外部告警。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/finance")
public class AdminFinanceController {
    private final MultiCurrencyFinanceService service;

    public AdminFinanceController(MultiCurrencyFinanceService service) {
        this.service = service;
    }

    @GetMapping("/exchange-rates")
    ApiResponse<List<Map<String, Object>>> rates() {
        return ApiResponse.ok(service.listRates());
    }

    @PostMapping("/exchange-rates")
    ApiResponse<Map<String, UUID>> createRate(@Valid @RequestBody RateRequest request) {
        UUID id = service.putRate(request.baseCurrency(), request.quoteCurrency(), request.rate(),
                request.effectiveAt(), request.source());
        return ApiResponse.ok(Map.of("id", id));
    }

    public record RateRequest(
            @NotBlank String baseCurrency,
            @NotBlank String quoteCurrency,
            @NotNull @Positive BigDecimal rate,
            @NotNull Instant effectiveAt,
            String source
    ) { }
}
