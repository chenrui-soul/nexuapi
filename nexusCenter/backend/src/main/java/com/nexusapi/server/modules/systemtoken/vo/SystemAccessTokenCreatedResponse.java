package com.nexusapi.server.modules.systemtoken.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 创建成功响应；完整 Secret 只在本次响应出现一次。 */
public record SystemAccessTokenCreatedResponse(
        SystemAccessTokenItemResponse token,
        @JsonProperty("secret") String secret
) {
}
