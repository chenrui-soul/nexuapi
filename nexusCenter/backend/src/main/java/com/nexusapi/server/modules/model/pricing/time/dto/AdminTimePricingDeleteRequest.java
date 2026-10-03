package com.nexusapi.server.modules.model.pricing.time.dto;

import jakarta.validation.constraints.Min;

/** 删除时段规则时携带乐观锁版本，防止删除刚被其他管理员修改的规则。 */
public record AdminTimePricingDeleteRequest(@Min(0) long version) {
}

