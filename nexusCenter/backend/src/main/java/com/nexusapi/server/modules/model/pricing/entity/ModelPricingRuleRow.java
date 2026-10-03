package com.nexusapi.server.modules.model.pricing.entity;

import java.math.BigDecimal;
import java.util.UUID;

/** 价格版本下按优先级匹配的一条条件规则。 */
public record ModelPricingRuleRow(
        UUID id,
        UUID pricingVersionId,
        int priority,
        String name,
        String matchConditionsJson,
        Integer billingType,
        BigDecimal unitPrice,
        BigDecimal priceMultiplier
) {
}
