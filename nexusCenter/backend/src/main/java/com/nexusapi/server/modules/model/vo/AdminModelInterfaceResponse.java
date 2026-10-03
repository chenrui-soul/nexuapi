package com.nexusapi.server.modules.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** 模型维护页展示的已支持接口摘要，不包含请求和响应字段树。 */
public record AdminModelInterfaceResponse(
        UUID id,
        @JsonProperty("interface_code") String interfaceCode,
        @JsonProperty("interface_name") String interfaceName,
        @JsonProperty("http_method") String httpMethod,
        @JsonProperty("public_path") String publicPath,
        String status
) {
}
