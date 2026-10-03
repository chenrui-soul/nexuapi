package com.nexusapi.server.modules.supplier.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 供应商可由管理员维护的完整配置快照；健康状态由 Wave 7A 独立维护。 */
public record AdminSupplierRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 24) @JsonProperty("supplier_type") String supplierType,
        @NotBlank @Size(max = 24) String status,
        @NotBlank @Size(max = 32) @JsonProperty("billing_mode") String billingMode,
        @NotBlank @Size(min = 3, max = 3) @JsonProperty("settlement_currency") String settlementCurrency,
        @Size(max = 500) @JsonProperty("disabled_reason") String disabledReason,
        @NotNull Map<String, Object> metadata,
        @PositiveOrZero Long version
) {
}
