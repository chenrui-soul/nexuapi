package com.nexusapi.server.modules.admin.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 管理端审计日志响应，不包含邮箱密文、密码摘要或任何凭证原文。 */
public record AdminAuditLogResponse(
        long id,
        @JsonProperty("actor_user_id") UUID actorUserId,
        @JsonProperty("actor_display_name") String actorDisplayName,
        @JsonProperty("actor_type") String actorType,
        String action,
        @JsonProperty("resource_type") String resourceType,
        @JsonProperty("resource_id") String resourceId,
        @JsonProperty("before_data") Map<String, Object> beforeData,
        @JsonProperty("after_data") Map<String, Object> afterData,
        @JsonProperty("ip_address") String ipAddress,
        @JsonProperty("user_agent_hash") String userAgentHash,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("created_at") Instant createdAt
) {
}
