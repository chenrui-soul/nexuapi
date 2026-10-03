package com.nexusapi.server.modules.model.pricing.time.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 请求进入平台时确定的时段倍率快照；预冻结、重试和最终结算必须复用同一个实例。 */
public record TimePricingSnapshot(
        UUID ruleId,
        String ruleName,
        BigDecimal multiplier,
        Instant pricingTime,
        String timezone
) {
    private static final BigDecimal ONE = new BigDecimal("1.0000000000");
    private static final String BUSINESS_TIMEZONE = "Asia/Shanghai";

    /** 未命中规则时仍保留计价时间，便于账单追溯本次为何使用 1 倍。 */
    public static TimePricingSnapshot none(Instant pricingTime) {
        return new TimePricingSnapshot(null, null, ONE, pricingTime, BUSINESS_TIMEZONE);
    }
}

