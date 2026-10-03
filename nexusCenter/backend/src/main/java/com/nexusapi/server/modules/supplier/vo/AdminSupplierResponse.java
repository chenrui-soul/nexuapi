package com.nexusapi.server.modules.supplier.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 供应商管理响应，不包含渠道凭证、合同账号或其他结算秘密。 */
public record AdminSupplierResponse(
        UUID id,
        String code,
        String name,
        @JsonProperty("supplier_type") String supplierType,
        String status,
        @JsonProperty("health_status") String healthStatus,
        @JsonProperty("billing_mode") String billingMode,
        @JsonProperty("settlement_currency") String settlementCurrency,
        @JsonProperty("disabled_reason") String disabledReason,
        @JsonProperty("disabled_at") Instant disabledAt,
        @JsonProperty("last_health_checked_at") Instant lastHealthCheckedAt,
        Map<String, Object> metadata,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
