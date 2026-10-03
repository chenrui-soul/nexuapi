package com.nexusapi.server.modules.model.application;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 模型市场分页结果；未按服务分组筛选时分组摘要字段为空。 */
public record ModelMarketResponse(
        List<ModelMarketItem> items,
        long total,
        int page,
        @JsonProperty("page_size") int pageSize,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        @JsonProperty("service_group_name") String serviceGroupName,
        @JsonProperty("price_multiplier") BigDecimal priceMultiplier
) {
}
