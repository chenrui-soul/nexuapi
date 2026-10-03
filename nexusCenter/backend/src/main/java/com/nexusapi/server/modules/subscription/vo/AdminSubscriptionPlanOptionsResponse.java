package com.nexusapi.server.modules.subscription.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 套餐编辑器的安全候选项，只包含当前可售服务分组和公开启用模型。 */
public record AdminSubscriptionPlanOptionsResponse(
        @JsonProperty("service_groups") List<ServiceGroupOption> serviceGroups,
        List<ModelOption> models
) {
    /** 服务分组候选项。 */
    public record ServiceGroupOption(
            UUID id,
            String code,
            String name,
            String audience,
            @JsonProperty("price_multiplier") BigDecimal priceMultiplier
    ) {
    }

    /** 模型候选项。 */
    public record ModelOption(
            UUID id,
            @JsonProperty("public_name") String publicName,
            @JsonProperty("display_name") String displayName,
            String provider,
            @JsonProperty("capability_type") String capabilityType
    ) {
    }
}
