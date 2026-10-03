package com.nexusapi.server.modules.billing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/** 控制台可见的钱包白名单快照。 */
public record WalletBalanceResponse(
        @JsonProperty("permanent_credits") BigDecimal permanentCredits,
        @JsonProperty("expiring_credits") BigDecimal expiringCredits,
        @JsonProperty("frozen_credits") BigDecimal frozenCredits,
        @JsonProperty("available_credits") BigDecimal availableCredits,
        @JsonProperty("total_credits") BigDecimal totalCredits,
        long version,
        @JsonProperty("updated_at") Instant updatedAt
) {
}

