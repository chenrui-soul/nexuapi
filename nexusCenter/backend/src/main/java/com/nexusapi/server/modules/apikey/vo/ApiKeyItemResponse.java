package com.nexusapi.server.modules.apikey.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 用户端 API 令牌列表项。
 *
 * <p>用量字段使用字符串序列化，避免浏览器以浮点数解析时丢失计费精度。</p>
 */
public record ApiKeyItemResponse(
        UUID id,
        String name,
        @JsonProperty("masked_key") String maskedKey,
        String status,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        @JsonProperty("service_group_name") String serviceGroupName,
        @JsonProperty("default_group_id") UUID defaultGroupId,
        @JsonProperty("allowed_model_ids") List<UUID> allowedModelIds,
        @JsonProperty("allowed_group_ids") List<UUID> allowedGroupIds,
        @JsonProperty("ip_allowlist") List<String> ipAllowlist,
        @JsonProperty("rpm_limit") Integer rpmLimit,
        @JsonProperty("tpm_limit") Long tpmLimit,
        @JsonProperty("concurrency_limit") Integer concurrencyLimit,
        @JsonProperty("credit_limit") BigDecimal creditLimit,
        /** 已结算积分，已扣除已完成的退款。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("used_credits") BigDecimal usedCredits,
        /** 正在处理的请求已预冻结但尚未结算的积分。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("reserved_credits") BigDecimal reservedCredits,
        /** 设置令牌上限时的可用剩余积分；未设置上限时为 null。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("remaining_credits") BigDecimal remainingCredits,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("last_used_at") Instant lastUsedAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
