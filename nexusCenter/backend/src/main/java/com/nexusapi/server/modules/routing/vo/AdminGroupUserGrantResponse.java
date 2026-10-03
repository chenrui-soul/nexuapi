package com.nexusapi.server.modules.routing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 管理端特殊服务分组授权用户响应；只返回脱敏邮箱和必要状态。 */
public record AdminGroupUserGrantResponse(
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("masked_email") String maskedEmail,
        @JsonProperty("user_status") String userStatus,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("granted_at") Instant grantedAt,
        @JsonProperty("updated_at") Instant updatedAt
) {
}
