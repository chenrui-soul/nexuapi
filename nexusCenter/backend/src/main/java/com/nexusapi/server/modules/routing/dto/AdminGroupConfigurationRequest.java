package com.nexusapi.server.modules.routing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 路由分组配置快照。
 *
 * <p>供应商和开放模型由管理员直接选择，运行时根据模型能力匹配供应商接口；
 * 上游凭证仅允许写入，绝不会出现在响应中。</p>
 */
public record AdminGroupConfigurationRequest(
        @NotNull @PositiveOrZero @JsonProperty("group_version") Long groupVersion,
        @NotNull @Size(max = 2_000) @JsonProperty("model_ids") List<@NotNull UUID> modelIds,
        @NotNull @Size(max = 100) @JsonProperty("supplier_credentials")
        List<@jakarta.validation.Valid AdminGroupSupplierCredentialRequest> supplierCredentials
) {
}
