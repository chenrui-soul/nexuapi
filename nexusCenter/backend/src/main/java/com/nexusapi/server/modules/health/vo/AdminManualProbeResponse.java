package com.nexusapi.server.modules.health.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 管理员手动探测结果，仅返回固定分类和最终渠道状态。 */
public record AdminManualProbeResponse(
        @JsonProperty("channel_id") UUID channelId,
        String outcome,
        String category,
        @JsonProperty("latency_ms") int latencyMs,
        @JsonProperty("channel_status") String channelStatus,
        @JsonProperty("consecutive_failures") int consecutiveFailures,
        @JsonProperty("circuit_open_until") Instant circuitOpenUntil
) {
}
