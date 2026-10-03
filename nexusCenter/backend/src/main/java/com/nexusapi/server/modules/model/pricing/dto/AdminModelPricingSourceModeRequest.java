package com.nexusapi.server.modules.model.pricing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;

/** 管理员切换“人工定价”和“跟随上游”时提交的乐观锁请求。 */
public record AdminModelPricingSourceModeRequest(
        /** true 表示允许后续完整同步创建并激活上游价格版本。 */
        @JsonProperty("follow_upstream") boolean followUpstream,
        /** 当前模型 version，防止覆盖其他管理员刚发布的价格。 */
        @PositiveOrZero @JsonProperty("model_version") long modelVersion
) {
}
