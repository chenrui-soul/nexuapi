package com.nexusapi.server.modules.devicekey.support;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** 设备激活密钥生成器；只返回一次明文，持久化边界仅保存摘要/密文。 */
@Component
public final class DeviceActivationKeyGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public Generated generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = "nxd_" + ENCODER.encodeToString(bytes);
        return new Generated(secret, secret.substring(0, 12), secret.substring(secret.length() - 6), digest(secret));
    }

    public byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("Unable to hash device activation key", e); }
    }

    public record Generated(String secret, String prefix, String suffix, byte[] hash) {
        public Generated { hash = hash.clone(); }
        public byte[] hash() { return hash.clone(); }
    }
}
