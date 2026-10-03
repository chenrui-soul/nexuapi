package com.nexusapi.server.modules.auth.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthSessionResponse(
        AuthUserResponse user,
        @JsonProperty("expires_in") long expiresIn
) {
}
