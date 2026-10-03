package com.nexusapi.server.modules.model.pricing.time.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/** 数据库中的模型时段加价规则；daysOfWeekCsv 使用 ISO 星期 1 至 7。 */
public record TimePricingRuleRow(
        UUID id,
        String name,
        BigDecimal multiplier,
        String daysOfWeekCsv,
        LocalTime startTime,
        LocalTime endTime,
        boolean enabled,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}

