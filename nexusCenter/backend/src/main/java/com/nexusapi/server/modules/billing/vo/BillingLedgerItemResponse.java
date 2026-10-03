package com.nexusapi.server.modules.billing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 用户账本查询仅返回资金变化和必要关联标识。 */
public record BillingLedgerItemResponse(
        UUID id,
        @JsonProperty("api_key_id") UUID apiKeyId,
        @JsonProperty("reservation_id") UUID reservationId,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("entry_type") String entryType,
        BigDecimal amount,
        @JsonProperty("permanent_delta") BigDecimal permanentDelta,
        @JsonProperty("expiring_delta") BigDecimal expiringDelta,
        @JsonProperty("frozen_delta") BigDecimal frozenDelta,
        @JsonProperty("available_after") BigDecimal availableAfter,
        @JsonProperty("frozen_after") BigDecimal frozenAfter,
        @JsonProperty("balance_after") BigDecimal balanceAfter,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("created_at") Instant createdAt
) {
}

