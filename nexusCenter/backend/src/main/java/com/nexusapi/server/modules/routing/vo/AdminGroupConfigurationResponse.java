package com.nexusapi.server.modules.routing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/** 分组开放模型、供应商关系与凭证状态的组合响应。 */
public record AdminGroupConfigurationResponse(
        AdminRoutingGroupResponse group,
        /** 同步分组取上游仍开放的模型；人工分组取管理员当前勾选的模型。 */
        @JsonProperty("default_model_ids") List<UUID> defaultModelIds,
        @JsonProperty("supplier_credentials") List<AdminGroupSupplierCredentialResponse> supplierCredentials
) {
}
