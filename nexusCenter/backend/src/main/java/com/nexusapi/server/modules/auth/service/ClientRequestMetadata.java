package com.nexusapi.server.modules.auth.service;

import com.nexusapi.server.common.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public record ClientRequestMetadata(String ipAddress, String userAgentHash, String requestId) {

    public static ClientRequestMetadata from(HttpServletRequest request) {
        return new ClientRequestMetadata(
                normalizeIp(request.getRemoteAddr()),
                digest(request.getHeader("User-Agent")),
                RequestIds.current()
        );
    }

    private static String normalizeIp(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return "0.0.0.0";
        }
        return value;
    }

    private static String digest(String value) {
        try {
            byte[] input = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash request metadata", exception);
        }
    }
}
