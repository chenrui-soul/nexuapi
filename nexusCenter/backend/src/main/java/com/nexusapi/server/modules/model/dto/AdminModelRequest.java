package com.nexusapi.server.modules.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 创建和更新模型时使用的完整配置快照；更新接口要求携带 version。 */
public record AdminModelRequest(
        @NotBlank @Size(max = 160) @JsonProperty("public_name") String publicName,
        @NotBlank @Size(max = 160) @JsonProperty("display_name") String displayName,
        @NotBlank @Size(max = 80) String provider,
        @NotBlank @Size(max = 24) @JsonProperty("capability_type") String capabilityType,
        @Size(max = 80) @JsonProperty("adapter_key") String adapterKey,
        @NotNull @Size(min = 1, max = 8) @JsonProperty("input_modalities") List<@NotBlank @Size(max = 24) String> inputModalities,
        @NotNull @Size(min = 1, max = 8) @JsonProperty("output_modalities") List<@NotBlank @Size(max = 24) String> outputModalities,
        @Positive @Max(10_000_000_000L) @JsonProperty("context_window") Long contextWindow,
        @Positive @Max(10_000_000_000L) @JsonProperty("max_output_tokens") Long maxOutputTokens,
        @JsonProperty("supports_streaming") boolean supportsStreaming,
        @JsonProperty("supports_tools") boolean supportsTools,
        @JsonProperty("supports_structured_output") boolean supportsStructuredOutput,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 10) @JsonProperty("input_price") BigDecimal inputPrice,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 10) @JsonProperty("output_price") BigDecimal outputPrice,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 10) @JsonProperty("cached_input_price") BigDecimal cachedInputPrice,
        @NotBlank @Size(max = 32) @JsonProperty("price_unit") String priceUnit,
        @Size(max = 20) @JsonProperty("interface_ids") List<UUID> interfaceIds,
        @JsonProperty("public_visible") boolean publicVisible,
        @NotBlank @Size(max = 24) String status,
        @PositiveOrZero Long version
) {
}
