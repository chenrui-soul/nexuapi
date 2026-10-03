package com.nexusapi.server.modules.requestlog.model;

import java.time.Instant;
import java.util.UUID;

/** 一次真实上游调用尝试的脱敏快照，用于供应商稳定性统计。 */
public record GatewayUpstreamAttemptLog(
        UUID id,
        String requestId,
        UUID supplierId,
        UUID channelId,
        UUID channelModelId,
        UUID modelId,
        int attemptNo,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        String outcome,
        Integer upstreamStatus,
        String errorCategory,
        String errorSummary
) {
}
