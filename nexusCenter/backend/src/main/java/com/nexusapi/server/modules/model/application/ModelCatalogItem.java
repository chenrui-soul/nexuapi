package com.nexusapi.server.modules.model.application;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

public record ModelCatalogItem(
        UUID id,
        @JsonProperty("public_name") String publicName,
        @JsonProperty("display_name") String displayName,
        String provider,
        @JsonProperty("capability_type") String capabilityType,
        @JsonProperty("context_window") Long contextWindow,
        @JsonProperty("supports_streaming") boolean supportsStreaming,
        @JsonProperty("supports_tools") boolean supportsTools,
        @JsonProperty("input_price") BigDecimal inputPrice,
        @JsonProperty("output_price") BigDecimal outputPrice,
        @JsonProperty("price_unit") String priceUnit
) {
}

