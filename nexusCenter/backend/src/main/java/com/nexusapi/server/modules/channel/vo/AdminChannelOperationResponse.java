package com.nexusapi.server.modules.channel.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 管理端可绑定的平台注册能力；它定义执行入口，不读取接口文档表。 */
public record AdminChannelOperationResponse(
        @JsonProperty("operation_code") String operationCode,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("capability_type") String capabilityType,
        @JsonProperty("request_method") String requestMethod,
        @JsonProperty("public_path") String publicPath
) {
}
