package com.nexusapi.server.modules.routing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/** 用户创建 API 令牌时可自主选择的公开服务分组摘要。 */
public record PublicServiceGroupResponse(
        UUID id,
        String code,
        String name,
        String description,
        @JsonProperty("price_multiplier") BigDecimal priceMultiplier,
        @JsonProperty("model_count") long modelCount
) {
}
