package com.nexusapi.server.modules.model.application;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/** 当前用户可见服务分组下的模型最终售价；不包含供应商成本、渠道或上游凭证。 */
public record ModelMarketGroupPrice(
        UUID id,
        String code,
        String name,
        String description,
        @JsonProperty("price_multiplier") BigDecimal priceMultiplier,
        @JsonProperty("effective_input_price") BigDecimal effectiveInputPrice,
        @JsonProperty("effective_output_price") BigDecimal effectiveOutputPrice,
        @JsonProperty("effective_cached_input_price") BigDecimal effectiveCachedInputPrice,
        @JsonProperty("effective_unit_price") BigDecimal effectiveUnitPrice
) {
}
