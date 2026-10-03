package com.nexusapi.server.modules.health.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 渠道或分组健康历史响应。 */
public record AdminHealthCheckResponse(
        long id,
        @JsonProperty("target_type") String targetType,
        @JsonProperty("target_id") UUID targetId,
        @JsonProperty("target_name") String targetName,
        String status,
        @JsonProperty("latency_ms") Integer latencyMs,
        @JsonProperty("error_summary") String errorSummary,
        @JsonProperty("checked_at") Instant checkedAt
) {
}
