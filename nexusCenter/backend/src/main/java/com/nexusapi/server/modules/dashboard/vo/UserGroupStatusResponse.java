package com.nexusapi.server.modules.dashboard.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 用户可见服务分组状态，统计来源为每个分组最近最多 60 次真实业务请求。 */
public record UserGroupStatusResponse(
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("sample_limit") int sampleLimit,
        List<GroupItem> groups
) {
    /** 单个服务分组的用户安全状态。 */
    public record GroupItem(
            String id,
            String name,
            String description,
            @JsonProperty("price_multiplier") @JsonFormat(shape = JsonFormat.Shape.STRING)
            BigDecimal priceMultiplier,
            String status,
            BigDecimal availability,
            @JsonProperty("average_latency_ms") BigDecimal averageLatencyMs,
            @JsonProperty("latency_p95_ms") Long latencyP95Ms,
            @JsonProperty("available_model_count") long availableModelCount,
            @JsonProperty("total_model_count") long totalModelCount,
            @JsonProperty("sample_count") long sampleCount,
            @JsonProperty("last_request_at") Instant lastRequestAt,
            List<HistoryItem> history
    ) {
    }

    /** 最近真实请求的状态条目，不包含用户、供应商、渠道和错误原文。 */
    public record HistoryItem(
            String status,
            @JsonProperty("occurred_at") Instant occurredAt,
            @JsonProperty("duration_ms") Long durationMs,
            @JsonProperty("status_code") Integer statusCode
    ) {
    }
}
