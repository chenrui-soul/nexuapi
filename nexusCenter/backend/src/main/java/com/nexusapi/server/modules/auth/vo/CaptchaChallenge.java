package com.nexusapi.server.modules.auth.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CaptchaChallenge(
        @JsonProperty("challenge_id") String challengeId,
        String image,
        @JsonProperty("expires_in") long expiresIn
) {
}
