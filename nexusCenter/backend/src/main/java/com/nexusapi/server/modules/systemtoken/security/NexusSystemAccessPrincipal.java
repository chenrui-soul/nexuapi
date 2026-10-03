package com.nexusapi.server.modules.systemtoken.security;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 通过独立系统访问令牌认证后的只读控制面身份。 */
public record NexusSystemAccessPrincipal(
        UUID tokenId,
        UUID userId,
        String maskedToken,
        List<String> scopes,
        List<String> ipAllowlist,
        Instant expiresAt
) implements Principal {
    public NexusSystemAccessPrincipal {
        scopes = List.copyOf(scopes);
        ipAllowlist = List.copyOf(ipAllowlist);
    }

    public boolean hasScope(String scope) { return scopes.contains(scope); }

    @Override
    public String getName() { return tokenId.toString(); }
}
