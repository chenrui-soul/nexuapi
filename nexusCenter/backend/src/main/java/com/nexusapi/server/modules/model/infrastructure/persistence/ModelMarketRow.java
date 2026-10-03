package com.nexusapi.server.modules.model.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.UUID;

/** 模型市场查询的内部行；该类型不会直接作为 API 响应返回。 */
public record ModelMarketRow(
        UUID id,
        String publicName,
        String displayName,
        String provider,
        String capabilityType,
        Long contextWindow,
        Long maxOutputTokens,
        boolean supportsStreaming,
        boolean supportsTools,
        boolean supportsStructuredOutput,
        UUID serviceGroupId,
        String serviceGroupName,
        BigDecimal priceMultiplier,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        BigDecimal cachedInputPrice,
        String priceUnit,
        int billingType,
        BigDecimal unitPrice,
        BigDecimal displayOriginalPrice,
        long inputTokenRatio,
        long outputTokenRatio,
        long audioInputTokenRatio,
        long audioOutputTokenRatio,
        long cachedInputTokenRatio,
        long cacheWrite5mTokenRatio,
        long cacheWrite1hTokenRatio,
        String chargeDesc,
        UUID activePricingVersionId,
        UUID recommendedServiceGroupId,
        String recommendedServiceGroupName,
        BigDecimal recommendedPriceMultiplier
) {
}
