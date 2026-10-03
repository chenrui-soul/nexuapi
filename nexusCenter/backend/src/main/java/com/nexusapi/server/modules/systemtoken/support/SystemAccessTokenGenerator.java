package com.nexusapi.server.modules.systemtoken.support;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/** 生成带独立 nx-sys 前缀的 32 字节随机系统访问令牌。 */
@Component
public class SystemAccessTokenGenerator {
    private static final int RANDOM_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();
    private final SystemAccessTokenHasher hasher;

    public SystemAccessTokenGenerator(SystemAccessTokenHasher hasher) {
        this.hasher = hasher;
    }

    public GeneratedSystemAccessToken generate() {
        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        int version = hasher.activeVersion();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String secret = "nx-sys-v" + version + '_' + payload;
        return new GeneratedSystemAccessToken(
                secret,
                secret.substring(0, Math.min(16, secret.length())),
                secret.substring(secret.length() - 8),
                hasher.hash(version, secret),
                version
        );
    }
}
