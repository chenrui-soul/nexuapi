package com.nexusapi.server.modules.gateway.security;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Gateway 公共调用身份；允许用户 API 令牌和控制台创作空间复用同一调用、计费与日志链路。 */
public interface GatewayCallerPrincipal {
    UUID apiKeyId();
    UUID userId();
    UUID serviceGroupId();
    UUID defaultGroupId();
    List<UUID> allowedModelIds();
    List<UUID> allowedGroupIds();
    List<String> ipAllowlist();
    Integer rpmLimit();
    Long tpmLimit();
    Integer concurrencyLimit();
    BigDecimal creditLimit();
    Instant expiresAt();

    /** 控制台调用没有 api_key_id，日志仍通过 user_id 完整归属当前用户。 */
    default boolean isConsoleSession() { return apiKeyId() == null; }
}
