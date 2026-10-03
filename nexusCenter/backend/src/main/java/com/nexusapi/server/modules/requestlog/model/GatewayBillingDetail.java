package com.nexusapi.server.modules.requestlog.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 一次请求采用的价格版本和计算快照；不包含请求正文或任何凭证。 */
public record GatewayBillingDetail(
        UUID id,
        String requestId,
        UUID userId,
        UUID apiKeyId,
        UUID modelId,
        UUID groupId,
        UUID pricingVersionId,
        UUID matchedRuleId,
        UUID contextTierId,
        String engineMode,
        int billingType,
        BigDecimal baseUnitPrice,
        BigDecimal effectiveUnitPrice,
        long inputTokenRatio,
        long outputTokenRatio,
        long cachedInputTokenRatio,
        long cacheWrite5mTokenRatio,
        long cacheWrite1hTokenRatio,
        long audioInputTokenRatio,
        long audioOutputTokenRatio,
        BigDecimal groupMultiplier,
        UUID timeRuleId,
        String timeRuleName,
        BigDecimal timeMultiplier,
        Instant pricingTime,
        BigDecimal baseUsageAmount,
        String usageSnapshotJson,
        String calculationSnapshotJson,
        BigDecimal legacyAmount,
        BigDecimal calculatedAmount,
        BigDecimal settledAmount,
        String status
) {
}
