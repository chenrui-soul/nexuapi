package com.nexusapi.server.modules.subscription.dto;

import jakarta.validation.constraints.Min;

/** 归档套餐时携带乐观锁版本，防止覆盖其他管理员刚完成的修改。 */
public record AdminSubscriptionPlanArchiveRequest(@Min(0) long version) {
}
