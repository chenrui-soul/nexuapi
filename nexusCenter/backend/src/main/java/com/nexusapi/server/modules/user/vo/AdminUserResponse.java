package com.nexusapi.server.modules.user.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理端用户响应；邮箱只返回脱敏值，密文、查询摘要和密码摘要永不出库。 */
public record AdminUserResponse(
        UUID id,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("masked_email") String maskedEmail,
        String status,
        List<String> roles,
        @JsonProperty("email_verified") boolean emailVerified,
        @JsonProperty("last_login_at") Instant lastLoginAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
