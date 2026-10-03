package com.nexusapi.server.modules.routing.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Gateway 使用的不可变条件价格规则。 */
public record RuntimePricingRuleRow(
        UUID id,
        int priority,
        String name,
        String matchConditionsJson,
        Integer billingType,
        BigDecimal unitPrice,
        BigDecimal priceMultiplier
) {
}
