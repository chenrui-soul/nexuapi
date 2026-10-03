package com.nexusapi.server.modules.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** 模型列表中展示的服务分组归属和上游健康摘要。 */
public record AdminModelGroupResponse(
        UUID id,
        String code,
        String name,
        @JsonProperty("source_status") String sourceStatus,
        @JsonProperty("upstream_last_status") Integer upstreamLastStatus,
        @JsonProperty("upstream_success_rate") Integer upstreamSuccessRate
) {
}
