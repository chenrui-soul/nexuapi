package com.nexusapi.server.modules.subscription.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 用户订阅页所需的套餐目录和当前订阅完整快照。 */
public record SubscriptionOverviewResponse(
        List<PlanItem> plans,
        @JsonProperty("current_subscription") CurrentSubscription currentSubscription,
        @JsonProperty("mock_purchase_enabled") boolean mockPurchaseEnabled
) {
    public record PlanItem(
            UUID id,
            String code,
            String name,
            String description,
            @JsonProperty("billing_cycle") String billingCycle,
            BigDecimal price,
            @JsonProperty("included_credits") BigDecimal includedCredits,
            @JsonProperty("concurrency_limit") Integer concurrencyLimit,
            List<String> features,
            @JsonProperty("service_groups") List<ServiceGroupItem> serviceGroups,
            List<ModelItem> models,
            boolean featured
    ) { }

    public record CurrentSubscription(
            UUID id,
            UUID planId,
            String planName,
            String billingCycle,
            String status,
            Instant startsAt,
            Instant expiresAt,
            boolean autoRenew,
            Instant cancelledAt,
            BigDecimal includedCredits,
            BigDecimal usedCredits,
            BigDecimal frozenCredits,
            BigDecimal remainingCredits,
            BigDecimal expiredCredits,
            Instant refundRequestedAt,
            Instant refundCompletedAt,
            BigDecimal refundAmount,
            BigDecimal refundableCredits,
            List<String> features,
            List<ServiceGroupItem> serviceGroups,
            List<ModelItem> models,
            long version
    ) { }

    public record ServiceGroupItem(UUID id, String code, String name) { }

    public record ModelItem(
            UUID id,
            @JsonProperty("public_name") String publicName,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("capability_type") String capabilityType
    ) { }
}
