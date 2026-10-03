package com.nexusapi.server.modules.model.pricing.time.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** 管理端时段倍率规则响应；倍率使用十进制字符串语义，前端最多展示三位小数。 */
public record AdminTimePricingRuleResponse(
        UUID id,
        String name,
        BigDecimal multiplier,
        @JsonProperty("days_of_week") List<Integer> daysOfWeek,
        @JsonProperty("start_time") @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonProperty("end_time") @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        boolean enabled,
        List<ModelSummary> models,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
    /** 规则只返回模型身份和能力类型，避免把供应商、渠道和成本带入计费设置页。 */
    public record ModelSummary(
            UUID id,
            @JsonProperty("public_name") String publicName,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("capability_type") String capabilityType
    ) {
    }
}

