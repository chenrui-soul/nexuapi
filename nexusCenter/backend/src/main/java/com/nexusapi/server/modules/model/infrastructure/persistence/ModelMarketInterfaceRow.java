package com.nexusapi.server.modules.model.infrastructure.persistence;

import java.util.UUID;

/** 模型详情接口文档查询行；JSON 字段树由应用层统一反序列化。 */
public record ModelMarketInterfaceRow(
        UUID id,
        String interfaceCode,
        String interfaceName,
        String interfaceVersion,
        String capabilityType,
        String transportMode,
        String httpMethod,
        String publicPath,
        String requestContentType,
        String description,
        String requestSchemaJson,
        String responseSchemaJson
) {
}
