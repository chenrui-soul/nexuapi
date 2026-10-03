package com.nexusapi.server.modules.model.application;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/** 用户模型市场展示项；当前筛选分组与默认推荐分组分开返回，避免混淆目录语义。 */
public record ModelMarketItem(
        UUID id,
        @JsonProperty("public_name") String publicName,
        @JsonProperty("display_name") String displayName,
        String provider,
        @JsonProperty("capability_type") String capabilityType,
        @JsonProperty("context_window") Long contextWindow,
        @JsonProperty("max_output_tokens") Long maxOutputTokens,
        @JsonProperty("supports_streaming") boolean supportsStreaming,
        @JsonProperty("supports_tools") boolean supportsTools,
        @JsonProperty("supports_structured_output") boolean supportsStructuredOutput,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        @JsonProperty("service_group_name") String serviceGroupName,
        @JsonProperty("price_multiplier") BigDecimal priceMultiplier,
        @JsonProperty("base_input_price") BigDecimal baseInputPrice,
        @JsonProperty("base_output_price") BigDecimal baseOutputPrice,
        @JsonProperty("base_cached_input_price") BigDecimal baseCachedInputPrice,
        @JsonProperty("effective_input_price") BigDecimal effectiveInputPrice,
        @JsonProperty("effective_output_price") BigDecimal effectiveOutputPrice,
        @JsonProperty("effective_cached_input_price") BigDecimal effectiveCachedInputPrice,
        @JsonProperty("price_unit") String priceUnit,
        @JsonProperty("billing_type") int billingType,
        @JsonProperty("billing_unit") String billingUnit,
        @JsonProperty("base_unit_price") BigDecimal baseUnitPrice,
        @JsonProperty("effective_unit_price") BigDecimal effectiveUnitPrice,
        @JsonProperty("display_original_price") BigDecimal displayOriginalPrice,
        @JsonProperty("input_token_ratio") long inputTokenRatio,
        @JsonProperty("output_token_ratio") long outputTokenRatio,
        @JsonProperty("audio_input_token_ratio") long audioInputTokenRatio,
        @JsonProperty("audio_output_token_ratio") long audioOutputTokenRatio,
        @JsonProperty("cached_input_token_ratio") long cachedInputTokenRatio,
        @JsonProperty("cache_write_5m_token_ratio") long cacheWrite5mTokenRatio,
        @JsonProperty("cache_write_1h_token_ratio") long cacheWrite1hTokenRatio,
        @JsonProperty("charge_desc") String chargeDesc,
        @JsonProperty("pricing_version_id") UUID pricingVersionId,
        String availability,
        /** 未选择服务分组时，当前用户可见分组中价格最低的推荐分组。 */
        @JsonProperty("recommended_service_group_id") UUID recommendedServiceGroupId,
        @JsonProperty("recommended_service_group_name") String recommendedServiceGroupName,
        @JsonProperty("recommended_price_multiplier") BigDecimal recommendedPriceMultiplier,
        @JsonProperty("recommended_effective_input_price") BigDecimal recommendedEffectiveInputPrice,
        @JsonProperty("recommended_effective_output_price") BigDecimal recommendedEffectiveOutputPrice,
        @JsonProperty("recommended_effective_cached_input_price") BigDecimal recommendedEffectiveCachedInputPrice,
        @JsonProperty("recommended_effective_unit_price") BigDecimal recommendedEffectiveUnitPrice
) {
}
