package com.nexusapi.server.modules.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 管理员修改模型市场自动同步开关和周期时提交的乐观锁快照。 */
public record ModelSyncSettingsRequest(
        boolean enabled,
        @Min(15) @Max(10_080) @JsonProperty("interval_minutes") int intervalMinutes,
        @NotNull @PositiveOrZero Long version
) {
}
