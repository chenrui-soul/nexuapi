package com.nexusapi.server.modules.subscription.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 管理员新增或编辑订阅套餐时提交的完整配置快照。 */
public record AdminSubscriptionPlanRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 24) @JsonProperty("billing_cycle") String billingCycle,
        @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal price,
        @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 12)
        @JsonProperty("included_credits") BigDecimal includedCredits,
        @Min(1) @Max(100_000) @JsonProperty("concurrency_limit") Integer concurrencyLimit,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 120) String> features,
        @NotBlank @Size(max = 24) String status,
        @Min(0) @Max(100_000) @JsonProperty("display_order") int displayOrder,
        boolean featured,
        @NotNull @Size(max = 100) @JsonProperty("service_group_ids") List<UUID> serviceGroupIds,
        @NotNull @Size(max = 2_000) @JsonProperty("model_ids") List<UUID> modelIds,
        @Min(0) Long version
) {
}
