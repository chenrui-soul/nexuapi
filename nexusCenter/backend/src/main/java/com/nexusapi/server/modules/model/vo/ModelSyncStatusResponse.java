package com.nexusapi.server.modules.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** 管理端模型同步控制面板所需的完整状态。 */
public record ModelSyncStatusResponse(
        boolean enabled,
        @JsonProperty("interval_minutes") int intervalMinutes,
        @JsonProperty("next_run_at") Instant nextRunAt,
        boolean running,
        long version,
        @JsonProperty("last_run") ModelSyncRunResponse lastRun
) {
}
