package com.nexusapi.server.modules.requestlog.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次网关调用的脱敏持久化快照。
 *
 * <p>载荷字段只保存经过递归脱敏且限长的排障副本；Authorization、Cookie、令牌、密钥和二进制内容永不保存。</p>
 */
public record GatewayRequestLog(
        UUID id,
        String requestId,
        UUID userId,
        UUID apiKeyId,
        UUID modelId,
        UUID supplierId,
        UUID channelId,
        UUID channelModelId,
        UUID groupId,
        String publicModel,
        String upstreamModel,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        int statusCode,
        String platformErrorCode,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        BigDecimal billedAmount,
        BigDecimal priceMultiplier,
        BigDecimal supplierInputPrice,
        BigDecimal supplierCachedInputPrice,
        BigDecimal supplierOutputPrice,
        BigDecimal supplierCostAmount,
        String supplierCostCurrency,
        BigDecimal grossMarginAmount,
        String supplierErrorCategory,
        boolean streaming,
        int retryCount,
        String clientIp,
        String userAgentHash,
        String upstreamErrorSummary,
        String routeSwitchReason,
        String requestSummaryJson,
        String responseSummaryJson,
        String requestDetailJson,
        String responseDetailJson,
        long requestPayloadSize,
        long responsePayloadSize
) {
}
