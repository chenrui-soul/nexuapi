package com.nexusapi.server.modules.health.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 管理端分组实时健康聚合响应。 */
public record AdminGroupHealthResponse(
        @JsonProperty("group_id") UUID groupId,
        @JsonProperty("group_code") String groupCode,
        @JsonProperty("group_name") String groupName,
        @JsonProperty("configuration_status") String configurationStatus,
        @JsonProperty("health_status") String healthStatus,
        @JsonProperty("configured_route_count") int configuredRouteCount,
        @JsonProperty("available_route_count") int availableRouteCount,
        @JsonProperty("latest_checked_at") Instant latestCheckedAt
) {
}
