package com.nexusapi.server.modules.apikey.security;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.nexusapi.server.modules.gateway.security.GatewayCallerPrincipal;

/**
 * 通过 Bearer API 令牌鉴权后的网关身份。
 *
 * <p>Principal 只携带下游路由、限流和计费所需的白名单字段，不包含完整 Secret 或 HMAC 摘要。</p>
 */
public record NexusApiKeyPrincipal(
        UUID apiKeyId,
        UUID userId,
        String maskedKey,
        UUID serviceGroupId,
        UUID defaultGroupId,
        List<UUID> allowedModelIds,
        List<UUID> allowedGroupIds,
        List<String> ipAllowlist,
        Integer rpmLimit,
        Long tpmLimit,
        Integer concurrencyLimit,
        BigDecimal creditLimit,
        Instant expiresAt
) implements Principal, GatewayCallerPrincipal {

    public NexusApiKeyPrincipal {
        allowedModelIds = List.copyOf(allowedModelIds);
        allowedGroupIds = List.copyOf(allowedGroupIds);
        ipAllowlist = List.copyOf(ipAllowlist);
    }

    @Override
    public String getName() {
        return apiKeyId.toString();
    }
}
