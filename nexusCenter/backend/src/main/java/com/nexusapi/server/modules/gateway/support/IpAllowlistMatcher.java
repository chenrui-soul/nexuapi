package com.nexusapi.server.modules.gateway.support;

import java.net.InetAddress;
import java.util.List;

/** 使用纯 IP 字面量和位掩码匹配 API 令牌 CIDR 白名单，不触发 DNS 查询。 */
public final class IpAllowlistMatcher {
    private IpAllowlistMatcher() {
    }

    public static boolean isAllowed(String clientIp, List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return true;
        }
        try {
            if (!isIpLiteral(clientIp)) {
                return false;
            }
            byte[] address = InetAddress.getByName(clientIp).getAddress();
            for (String cidr : cidrs) {
                if (matches(address, cidr)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean matches(byte[] address, String cidr) throws Exception {
        int separator = cidr.lastIndexOf('/');
        if (separator <= 0 || separator == cidr.length() - 1) {
            return false;
        }
        String networkValue = cidr.substring(0, separator);
        if (!isIpLiteral(networkValue)) {
            return false;
        }
        byte[] network = InetAddress.getByName(networkValue).getAddress();
        if (network.length != address.length) {
            return false;
        }
        int prefix = Integer.parseInt(cidr.substring(separator + 1));
        if (prefix < 0 || prefix > address.length * 8) {
            return false;
        }
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (address[index] != network[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    private static boolean isIpLiteral(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return false;
        }
        return value.indexOf(':') >= 0 || value.chars().allMatch(character ->
                (character >= '0' && character <= '9') || character == '.');
    }
}
