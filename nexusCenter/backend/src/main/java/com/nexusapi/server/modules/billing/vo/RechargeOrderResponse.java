package com.nexusapi.server.modules.billing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RechargeOrderResponse(
        UUID id,
        @JsonProperty("order_no") String orderNo,
        BigDecimal amount,
        String currency,
        String status,
        @JsonProperty("payment_provider") String paymentProvider,
        @JsonProperty("provider_order_id") String providerOrderId,
        @JsonProperty("credited_points") BigDecimal creditedPoints,
        @JsonProperty("pay_url") String payUrl,
        @JsonProperty("paid_at") Instant paidAt,
        @JsonProperty("created_at") Instant createdAt
) {
}
