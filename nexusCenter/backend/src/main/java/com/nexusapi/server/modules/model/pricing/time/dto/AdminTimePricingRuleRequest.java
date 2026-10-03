package com.nexusapi.server.modules.model.pricing.time.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** 管理员创建或编辑模型时段倍率的完整请求。 */
public record AdminTimePricingRuleRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull @DecimalMin("1") @DecimalMax("100") @Digits(integer = 3, fraction = 10)
        BigDecimal multiplier,
        @JsonProperty("days_of_week") @NotEmpty @Size(max = 7)
        List<@NotNull @Min(1) @Max(7) Integer> daysOfWeek,
        @JsonProperty("start_time") @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonProperty("end_time") @NotNull @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        boolean enabled,
        @JsonProperty("model_ids") @NotEmpty @Size(max = 500) List<@NotNull @Valid UUID> modelIds,
        @Min(0) long version
) {
}

