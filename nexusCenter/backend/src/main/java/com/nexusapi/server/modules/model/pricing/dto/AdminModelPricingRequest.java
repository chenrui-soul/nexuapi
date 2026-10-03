package com.nexusapi.server.modules.model.pricing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 管理员发布一个不可变价格版本时提交的完整快照。 */
public record AdminModelPricingRequest(
        @Min(1) @Max(6) @JsonProperty("billing_type") int billingType,
        @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 12)
        @JsonProperty("unit_price") BigDecimal unitPrice,
        @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 12)
        @JsonProperty("display_original_price") BigDecimal displayOriginalPrice,
        @PositiveOrZero @Max(1_000_000_000_000L)
        @JsonProperty("input_token_ratio") long inputTokenRatio,
        @PositiveOrZero @Max(1_000_000_000_000L)
        @JsonProperty("output_token_ratio") long outputTokenRatio,
        @PositiveOrZero @Max(1_000_000_000_000L)
        @JsonProperty("audio_input_token_ratio") long audioInputTokenRatio,
        @PositiveOrZero @Max(1_000_000_000_000L)
        @JsonProperty("audio_output_token_ratio") long audioOutputTokenRatio,
        @PositiveOrZero @Max(1_000_000_000_000L)
        @JsonProperty("cached_input_token_ratio") long cachedInputTokenRatio,
        @PositiveOrZero @Max(1_000_000_000_000L)
        @JsonProperty("cache_write_5m_token_ratio") long cacheWrite5mTokenRatio,
        @PositiveOrZero @Max(1_000_000_000_000L)
        @JsonProperty("cache_write_1h_token_ratio") long cacheWrite1hTokenRatio,
        @Size(max = 1000) @JsonProperty("charge_desc") String chargeDesc,
        @Min(0) @Max(2) @JsonProperty("context_tier_mode") int contextTierMode,
        @NotBlank @Size(max = 16) @JsonProperty("unmatched_behavior") String unmatchedBehavior,
        @NotNull @Size(max = 100) List<@Valid PricingRuleInput> rules,
        @NotNull @Size(max = 50) @JsonProperty("context_tiers") List<@Valid ContextTierInput> contextTiers,
        @Size(max = 500) @JsonProperty("change_note") String changeNote,
        @PositiveOrZero @JsonProperty("model_version") long modelVersion
) {
    /** 条件只允许扁平字符串键值，避免把任意表达式或脚本带入计费引擎。 */
    public record PricingRuleInput(
            @PositiveOrZero int priority,
            @NotBlank @Size(max = 120) String name,
            @NotEmpty @Size(max = 16) @JsonProperty("match_conditions") Map<String, @NotBlank @Size(max = 160) String> matchConditions,
            @Min(1) @Max(6) @JsonProperty("billing_type") Integer billingType,
            @DecimalMin("0") @Digits(integer = 18, fraction = 12) @JsonProperty("unit_price") BigDecimal unitPrice,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 10)
            @JsonProperty("price_multiplier") BigDecimal priceMultiplier
    ) {
    }

    public record ContextTierInput(
            @PositiveOrZero int priority,
            @PositiveOrZero @JsonProperty("min_input_tokens") long minInputTokens,
            @PositiveOrZero @JsonProperty("max_input_tokens") Long maxInputTokens,
            @PositiveOrZero @Max(1_000_000_000_000L) @JsonProperty("input_ratio") long inputRatio,
            @PositiveOrZero @Max(1_000_000_000_000L) @JsonProperty("output_ratio") long outputRatio,
            @PositiveOrZero @Max(1_000_000_000_000L) @JsonProperty("cached_input_ratio") long cachedInputRatio,
            @PositiveOrZero @Max(1_000_000_000_000L) @JsonProperty("cache_write_5m_ratio") long cacheWrite5mRatio,
            @PositiveOrZero @Max(1_000_000_000_000L) @JsonProperty("cache_write_1h_ratio") long cacheWrite1hRatio
    ) {
    }
}
