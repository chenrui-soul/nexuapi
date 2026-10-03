package com.nexusapi.server.modules.routing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 管理端服务分组供应商资源和凭证的脱敏状态。 */
public record AdminGroupSupplierCredentialResponse(
        @JsonProperty("supplier_id") UUID supplierId,
        @JsonProperty("supplier_name") String supplierName,
        int priority,
        int weight,
        @JsonProperty("credential_configured") boolean credentialConfigured,
        @JsonProperty("credential_active") boolean credentialActive,
        @JsonProperty("credential_fingerprint") String credentialFingerprint,
        @JsonProperty("credential_updated_at") Instant credentialUpdatedAt
) {
}
