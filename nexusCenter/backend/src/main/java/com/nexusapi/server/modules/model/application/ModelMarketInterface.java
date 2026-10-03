package com.nexusapi.server.modules.model.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusapi.server.modules.protocol.dto.ProtocolSchemaField;

import java.util.List;
import java.util.UUID;

/** 模型详情页公开接口文档；字段关系只用于展示，不参与真实 Gateway 路由。 */
public record ModelMarketInterface(
        UUID id,
        @JsonProperty("interface_code") String interfaceCode,
        @JsonProperty("interface_name") String interfaceName,
        @JsonProperty("interface_version") String interfaceVersion,
        @JsonProperty("capability_type") String capabilityType,
        @JsonProperty("transport_mode") String transportMode,
        @JsonProperty("http_method") String httpMethod,
        @JsonProperty("public_path") String publicPath,
        @JsonProperty("request_content_type") String requestContentType,
        String description,
        @JsonProperty("request_fields") List<ProtocolSchemaField> requestFields,
        @JsonProperty("response_fields") List<ProtocolSchemaField> responseFields
) {
}
