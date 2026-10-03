package com.nexusapi.server.modules.health.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 管理端渠道健康响应，不暴露上游地址、凭证或响应正文。 */
public record AdminChannelHealthResponse(
        @JsonProperty("channel_id") UUID channelId,
        @JsonProperty("channel_name") String channelName,
        @JsonProperty("supplier_name") String supplierName,
        @JsonProperty("channel_status") String channelStatus,
        @JsonProperty("health_probe_path") String healthProbePath,
        @JsonProperty("consecutive_failures") int consecutiveFailures,
        @JsonProperty("circuit_open_until") Instant circuitOpenUntil,
        @JsonProperty("latest_check_status") String latestCheckStatus,
        @JsonProperty("latest_latency_ms") Integer latestLatencyMs,
        @JsonProperty("latest_error_summary") String latestErrorSummary,
        @JsonProperty("latest_checked_at") Instant latestCheckedAt
) {
}
