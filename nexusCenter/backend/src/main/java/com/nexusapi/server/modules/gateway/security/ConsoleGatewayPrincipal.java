package com.nexusapi.server.modules.gateway.security;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 创作空间 Cookie Session 转换出的内部 Gateway 身份，不生成或持久化任何访问令牌。 */
public record ConsoleGatewayPrincipal(UUID userId, UUID serviceGroupId) implements GatewayCallerPrincipal {
    @Override public UUID apiKeyId() { return null; }
    @Override public UUID defaultGroupId() { return serviceGroupId; }
    @Override public List<UUID> allowedModelIds() { return List.of(); }
    @Override public List<UUID> allowedGroupIds() { return List.of(serviceGroupId); }
    @Override public List<String> ipAllowlist() { return List.of(); }
    @Override public Integer rpmLimit() { return 60; }
    @Override public Long tpmLimit() { return null; }
    @Override public Integer concurrencyLimit() { return 3; }
    @Override public BigDecimal creditLimit() { return null; }
    @Override public Instant expiresAt() { return null; }
}
