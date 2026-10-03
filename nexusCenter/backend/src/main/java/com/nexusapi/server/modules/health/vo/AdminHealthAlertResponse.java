package com.nexusapi.server.modules.health.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 分组健康告警生命周期响应。 */
public record AdminHealthAlertResponse(
        long id,
        @JsonProperty("group_id") UUID groupId,
        @JsonProperty("group_code") String groupCode,
        @JsonProperty("group_name") String groupName,
        @JsonProperty("alert_type") String alertType,
        String status,
        String severity,
        String title,
        String summary,
        @JsonProperty("occurrence_count") int occurrenceCount,
        @JsonProperty("notification_count") int notificationCount,
        @JsonProperty("suppressed_count") int suppressedCount,
        @JsonProperty("opened_at") Instant openedAt,
        @JsonProperty("last_seen_at") Instant lastSeenAt,
        @JsonProperty("last_notified_at") Instant lastNotifiedAt,
        @JsonProperty("resolved_at") Instant resolvedAt,
        @JsonProperty("updated_at") Instant updatedAt
) {
}
