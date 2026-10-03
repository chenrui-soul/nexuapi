package com.nexusapi.server.modules.model.pricing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;

/** 删除未使用历史价格版本时携带模型乐观锁版本。 */
public record AdminModelPricingDeleteRequest(
        @PositiveOrZero @JsonProperty("model_version") long modelVersion
) {
}
