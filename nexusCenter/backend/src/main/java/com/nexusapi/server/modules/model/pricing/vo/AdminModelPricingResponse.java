package com.nexusapi.server.modules.model.pricing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理端价格配置、当前生效版本和最近历史版本。 */
public record AdminModelPricingResponse(
        @JsonProperty("model_id") UUID modelId,
        @JsonProperty("active_pricing_version_id") UUID activePricingVersionId,
        @JsonProperty("pricing_mode") String pricingMode,
        @JsonProperty("pricing_source_hash") String pricingSourceHash,
        @JsonProperty("pricing_source_synced_at") Instant pricingSourceSyncedAt,
        @JsonProperty("model_version") long modelVersion,
        PricingVersion active,
        List<PricingVersion> versions
) {
    public record PricingVersion(
            UUID id,
            @JsonProperty("version_no") long versionNo,
            @JsonProperty("billing_type") int billingType,
            @JsonProperty("billing_unit") String billingUnit,
            @JsonProperty("unit_price") BigDecimal unitPrice,
            @JsonProperty("display_original_price") BigDecimal displayOriginalPrice,
            @JsonProperty("input_token_ratio") long inputTokenRatio,
            @JsonProperty("output_token_ratio") long outputTokenRatio,
            @JsonProperty("audio_input_token_ratio") long audioInputTokenRatio,
            @JsonProperty("audio_output_token_ratio") long audioOutputTokenRatio,
            @JsonProperty("cached_input_token_ratio") long cachedInputTokenRatio,
            @JsonProperty("cache_write_5m_token_ratio") long cacheWrite5mTokenRatio,
            @JsonProperty("cache_write_1h_token_ratio") long cacheWrite1hTokenRatio,
            @JsonProperty("charge_desc") String chargeDesc,
            @JsonProperty("context_tier_mode") int contextTierMode,
            @JsonProperty("unmatched_behavior") String unmatchedBehavior,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_hash") String sourceHash,
            @JsonProperty("source_observed_at") Instant sourceObservedAt,
            @JsonProperty("change_note") String changeNote,
            @JsonProperty("created_by") UUID createdBy,
            @JsonProperty("created_at") Instant createdAt,
            boolean active,
            List<PricingRule> rules,
            @JsonProperty("context_tiers") List<ContextTier> contextTiers
    ) {
    }

    public record PricingRule(
            UUID id,
            int priority,
            String name,
            @JsonProperty("match_conditions") Map<String, String> matchConditions,
            @JsonProperty("billing_type") Integer billingType,
            @JsonProperty("unit_price") BigDecimal unitPrice,
            @JsonProperty("price_multiplier") BigDecimal priceMultiplier
    ) {
    }

    public record ContextTier(
            UUID id,
            int priority,
            @JsonProperty("min_input_tokens") long minInputTokens,
            @JsonProperty("max_input_tokens") Long maxInputTokens,
            @JsonProperty("input_ratio") long inputRatio,
            @JsonProperty("output_ratio") long outputRatio,
            @JsonProperty("cached_input_ratio") long cachedInputRatio,
            @JsonProperty("cache_write_5m_ratio") long cacheWrite5mRatio,
            @JsonProperty("cache_write_1h_ratio") long cacheWrite1hRatio
    ) {
    }
}
