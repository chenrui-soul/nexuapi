package com.nexusapi.server.modules.routing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 服务分组直接关联供应商时的路由参数和上游凭证写入请求。 */
public record AdminGroupSupplierCredentialRequest(
        @NotNull @JsonProperty("supplier_id") UUID supplierId,
        @PositiveOrZero @Max(1_000_000) int priority,
        @Positive @Max(1_000_000) int weight,
        /** 首次关联时必填；更新时为空表示保留当前分组密钥。 */
        @Size(max = 4_096) String credential
) {
}
