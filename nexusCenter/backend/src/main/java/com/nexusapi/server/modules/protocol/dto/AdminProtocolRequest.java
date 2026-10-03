package com.nexusapi.server.modules.protocol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 管理员创建或更新接口文档时提交的完整配置；模型关联由模型维护接口单独保存。 */
public record AdminProtocolRequest(
        @NotBlank @Size(max = 64) @JsonProperty("interface_code") String interfaceCode,
        @NotBlank @Size(max = 120) @JsonProperty("interface_name") String interfaceName,
        @NotBlank @Size(max = 32) @JsonProperty("interface_version") String interfaceVersion,
        @NotBlank @Size(max = 24) @JsonProperty("capability_type") String capabilityType,
        @NotBlank @Size(max = 24) @JsonProperty("transport_mode") String transportMode,
        @NotBlank @Size(max = 8) @JsonProperty("http_method") String httpMethod,
        @NotBlank @Size(max = 240) @JsonProperty("public_path") String publicPath,
        @NotBlank @Size(max = 80) @JsonProperty("request_content_type") String requestContentType,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 24) String status,
        @NotNull @Valid @Size(max = 300) @JsonProperty("request_fields") List<ProtocolSchemaField> requestFields,
        @NotNull @Valid @Size(max = 300) @JsonProperty("response_fields") List<ProtocolSchemaField> responseFields,
        @PositiveOrZero Long version
) {
}
