package com.nexusapi.server.modules.routing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 计费分组完整配置快照。 */
public record AdminRoutingGroupRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotNull @DecimalMin("0.000001") @DecimalMax("999.999999") @Digits(integer = 3, fraction = 6)
        @JsonProperty("price_multiplier") BigDecimal priceMultiplier,
        @NotBlank @Size(max = 24) String audience,
        @NotBlank @Size(max = 24) String status,
        @PositiveOrZero Long version
) {
}

