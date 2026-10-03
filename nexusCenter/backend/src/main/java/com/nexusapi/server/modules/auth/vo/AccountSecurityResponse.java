package com.nexusapi.server.modules.auth.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** 用户可查看的账户安全摘要，禁止暴露 Session ID、IP、UA 和密码摘要。 */
public record AccountSecurityResponse(
        String email,
        @JsonProperty("email_verified") boolean emailVerified,
        @JsonProperty("last_login_at") Instant lastLoginAt,
        @JsonProperty("password_changed_at") Instant passwordChangedAt,
        @JsonProperty("active_session_count") int activeSessionCount
) {
}
