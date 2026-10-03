package com.nexusapi.server.modules.protocol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * API 接口请求或响应中的单个字段定义。
 *
 * <p>字段说明会直接用于管理员编辑页和用户接口文档，不参与上游凭证或路由配置。</p>
 */
public record ProtocolSchemaField(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String path,
        @NotBlank @Size(max = 24) String type,
        boolean required,
        @Size(max = 1000) String description,
        @JsonProperty("default_value") Object defaultValue,
        Object example,
        @Size(max = 50) @JsonProperty("enum_values") List<@Size(max = 200) String> enumValues,
        BigDecimal minimum,
        BigDecimal maximum,
        boolean deprecated,
        boolean sensitive,
        @Valid @Size(max = 100) List<ProtocolSchemaField> children
) {
}
