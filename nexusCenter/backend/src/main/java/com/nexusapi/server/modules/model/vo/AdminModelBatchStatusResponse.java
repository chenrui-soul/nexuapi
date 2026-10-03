package com.nexusapi.server.modules.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 模型批量启停结果，仅返回数量和目标状态，不回显模型配置或敏感信息。 */
public record AdminModelBatchStatusResponse(
        String status,
        @JsonProperty("requested_count") int requestedCount,
        @JsonProperty("updated_count") int updatedCount,
        @JsonProperty("unchanged_count") int unchangedCount
) {
}
