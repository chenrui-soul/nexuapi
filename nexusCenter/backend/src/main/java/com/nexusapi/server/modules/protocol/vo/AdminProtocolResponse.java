package com.nexusapi.server.modules.protocol.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusapi.server.modules.protocol.dto.ProtocolSchemaField;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理员接口文档详情；不包含供应商地址、上游 APIKey 或模型关联数据。 */
public record AdminProtocolResponse(
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
        String status,
        @JsonProperty("request_fields") List<ProtocolSchemaField> requestFields,
        @JsonProperty("response_fields") List<ProtocolSchemaField> responseFields,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
