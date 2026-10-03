package com.nexusapi.server.modules.subscription.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 订阅取消与退款结果；只返回用户需要了解的状态和金额。 */
public record SubscriptionCancellationResponse(
        @JsonProperty("subscription_id") UUID subscriptionId,
        String status,
        @JsonProperty("refund_amount") BigDecimal refundAmount,
        @JsonProperty("refundable_credits") BigDecimal refundableCredits,
        @JsonProperty("frozen_credits") BigDecimal frozenCredits,
        @JsonProperty("refund_pending") boolean refundPending,
        @JsonProperty("completed_at") Instant completedAt
) { }
