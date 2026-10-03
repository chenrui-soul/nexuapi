package com.nexusapi.server.modules.model.pricing.time.entity;

import java.util.UUID;

/** 管理端展示的时段规则模型关联，不包含模型价格或供应商信息。 */
public record TimePricingRuleModelRow(
        UUID ruleId,
        UUID modelId,
        String publicName,
        String displayName,
        String capabilityType
) {
}

