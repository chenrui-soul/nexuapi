package com.nexusapi.server.modules.apikey.support;

public record GeneratedApiKey(
        String secret,
        String prefix,
        String suffix,
        byte[] hash,
        int hashVersion
) {
}
