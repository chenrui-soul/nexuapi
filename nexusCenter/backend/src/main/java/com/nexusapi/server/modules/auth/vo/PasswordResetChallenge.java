package com.nexusapi.server.modules.auth.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 公开的密码重置挑战，不包含邮箱是否存在或邮件验证码。 */
public record PasswordResetChallenge(
        @JsonProperty("reset_id") String resetId,
        @JsonProperty("expires_in") long expiresIn
) {
}
