package com.nexusapi.server.modules.model.pricing.time.dto;

import jakarta.validation.constraints.Min;

/** 启用或停用时段规则时只提交目标状态和当前版本，避免覆盖其他字段。 */
public record AdminTimePricingStatusRequest(
        boolean enabled,
        @Min(0) long version
) {
}

