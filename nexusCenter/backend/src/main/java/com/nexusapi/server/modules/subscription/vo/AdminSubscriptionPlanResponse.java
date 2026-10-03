package com.nexusapi.server.modules.subscription.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理员套餐配置响应；只返回售卖权益，不返回用户订阅、账单或内部路由信息。 */
public record AdminSubscriptionPlanResponse(
        UUID id,
        String code,
        String name,
        String description,
        @JsonProperty("billing_cycle") String billingCycle,
        BigDecimal price,
        @JsonProperty("included_credits") BigDecimal includedCredits,
        @JsonProperty("concurrency_limit") Integer concurrencyLimit,
        List<String> features,
        String status,
        @JsonProperty("display_order") int displayOrder,
        boolean featured,
        @JsonProperty("service_groups") List<ServiceGroupSummary> serviceGroups,
        List<ModelSummary> models,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
    /** 套餐允许的服务分组摘要。 */
    public record ServiceGroupSummary(UUID id, String code, String name) {
    }

    /** 套餐允许的模型摘要。 */
    public record ModelSummary(
            UUID id,
            @JsonProperty("public_name") String publicName,
            @JsonProperty("display_name") String displayName,
            String provider,
            @JsonProperty("capability_type") String capabilityType
    ) {
    }
}
