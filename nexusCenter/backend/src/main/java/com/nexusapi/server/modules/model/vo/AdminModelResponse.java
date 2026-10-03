package com.nexusapi.server.modules.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理端模型配置响应，价格保持 BigDecimal 精度。 */
public record AdminModelResponse(
        UUID id,
        @JsonProperty("public_name") String publicName,
        @JsonProperty("display_name") String displayName,
        String provider,
        @JsonProperty("capability_type") String capabilityType,
        @JsonProperty("adapter_key") String adapterKey,
        @JsonProperty("input_modalities") List<String> inputModalities,
        @JsonProperty("output_modalities") List<String> outputModalities,
        @JsonProperty("context_window") Long contextWindow,
        @JsonProperty("max_output_tokens") Long maxOutputTokens,
        @JsonProperty("supports_streaming") boolean supportsStreaming,
        @JsonProperty("supports_tools") boolean supportsTools,
        @JsonProperty("supports_structured_output") boolean supportsStructuredOutput,
        @JsonProperty("input_price") BigDecimal inputPrice,
        @JsonProperty("output_price") BigDecimal outputPrice,
        @JsonProperty("cached_input_price") BigDecimal cachedInputPrice,
        @JsonProperty("price_unit") String priceUnit,
        @JsonProperty("billing_type") int billingType,
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
        @JsonProperty("active_pricing_version_id") UUID activePricingVersionId,
        @JsonProperty("public_visible") boolean publicVisible,
        String status,
        @JsonProperty("sync_source") String syncSource,
        @JsonProperty("source_model_key") String sourceModelKey,
        @JsonProperty("source_managed") boolean sourceManaged,
        @JsonProperty("source_last_seen_at") Instant sourceLastSeenAt,
        @JsonProperty("source_synced_at") Instant sourceSyncedAt,
        @JsonProperty("service_groups") List<AdminModelGroupResponse> serviceGroups,
        @JsonProperty("interfaces") List<AdminModelInterfaceResponse> interfaces,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
