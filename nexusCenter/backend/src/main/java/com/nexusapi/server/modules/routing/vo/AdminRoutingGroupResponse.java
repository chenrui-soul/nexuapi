package com.nexusapi.server.modules.routing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 管理端计费分组响应。 */
public record AdminRoutingGroupResponse(
        UUID id,
        String code,
        String name,
        String description,
        @JsonProperty("price_multiplier") BigDecimal priceMultiplier,
        String audience,
        String status,
        @JsonProperty("source_supplier_id") UUID sourceSupplierId,
        @JsonProperty("source_supplier_name") String sourceSupplierName,
        @JsonProperty("source_group_id") String sourceGroupId,
        @JsonProperty("source_group_name") String sourceGroupName,
        @JsonProperty("sync_source") String syncSource,
        @JsonProperty("source_status") String sourceStatus,
        @JsonProperty("source_rate") Long sourceRate,
        @JsonProperty("source_billing_type") Integer sourceBillingType,
        @JsonProperty("source_managed") boolean sourceManaged,
        @JsonProperty("source_last_seen_at") Instant sourceLastSeenAt,
        @JsonProperty("source_synced_at") Instant sourceSyncedAt,
        @JsonProperty("source_model_count") int sourceModelCount,
        @JsonProperty("healthy_model_count") int healthyModelCount,
        @JsonProperty("stale_model_count") int staleModelCount,
        @JsonProperty("authorized_user_count") int authorizedUserCount,
        /** 是否存在至少一条启用且已安全保存的分组专用供应商上游 API Key；不返回凭证内容。 */
        @JsonProperty("credential_configured") boolean credentialConfigured,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
