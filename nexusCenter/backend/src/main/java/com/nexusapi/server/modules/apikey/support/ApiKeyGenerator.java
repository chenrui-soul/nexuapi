package com.nexusapi.server.modules.apikey.support;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 使用密码学安全随机数生成 API 令牌，并同时产出一次性 Secret、脱敏前后缀和 HMAC 摘要。
 * 完整 Secret 只应返回给创建接口，不能持久化或写入日志。
 */
@Component
public class ApiKeyGenerator {
    private static final int RANDOM_BYTES = 32;
    private static final int PREFIX_LENGTH = 16;
    private static final int SUFFIX_LENGTH = 8;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ApiKeySecretHasher hasher;

    public ApiKeyGenerator(ApiKeySecretHasher hasher) {
        this.hasher = hasher;
    }

    public GeneratedApiKey generate() {
        // 32 字节随机熵配合无填充 Base64URL，适合放入 Authorization Bearer 头。
        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        int version = hasher.activeVersion();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String secret = "sk-nx-v" + version + '_' + encoded;
        return new GeneratedApiKey(
                secret,
                secret.substring(0, PREFIX_LENGTH),
                secret.substring(secret.length() - SUFFIX_LENGTH),
                hasher.hash(version, secret),
                version
        );
    }
}
