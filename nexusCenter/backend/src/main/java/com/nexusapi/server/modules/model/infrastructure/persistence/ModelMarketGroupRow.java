package com.nexusapi.server.modules.model.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.UUID;

/** 模型详情页可见服务分组查询行；最终售价由统一计费服务计算。 */
public record ModelMarketGroupRow(
        UUID id,
        String code,
        String name,
        String description,
        BigDecimal priceMultiplier
) {
}
