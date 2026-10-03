package com.nexusapi.server.modules.apikey.support;

import com.nexusapi.server.common.config.NexusProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 使用 API 令牌专用、可版本化的 HMAC-SHA256 密钥生成数据库查询摘要。
 *
 * <p>该密钥与邮箱等其他查找摘要隔离；保留旧版本密钥可支持平滑轮换和存量 Key 验证。</p>
 */
@Component
public class ApiKeySecretHasher {
    private final int activeVersion;
    private final Map<Integer, SecretKeySpec> keys;

    public ApiKeySecretHasher(NexusProperties properties) {
        NexusProperties.ApiKey configuration = properties.apiKey();
        this.activeVersion = configuration.activeHashVersion();
        this.keys = decodeKeys(configuration.hmacKeys());
        if (!keys.containsKey(activeVersion)) {
            throw new IllegalStateException("nexus.api-key.active-hash-version must reference a configured HMAC key");
        }
    }

    public int activeVersion() {
        return activeVersion;
    }

    /** 外部 Key 携带未知版本时应按鉴权失败处理，而不是抛出内部配置异常。 */
    public boolean supportsVersion(int version) {
        return keys.containsKey(version);
    }

    public byte[] hash(int version, String secret) {
        SecretKeySpec key = keys.get(version);
        if (key == null) {
            throw new IllegalArgumentException("Unsupported API key hash version");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);

            // 把用途和版本写入消息域，避免同一底层算法在不同上下文中产生可混用摘要。
            return mac.doFinal(("api-key:v" + version + ':' + secret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create API key hash", exception);
        }
    }

    private Map<Integer, SecretKeySpec> decodeKeys(Map<Integer, String> configuredKeys) {
        if (configuredKeys == null || configuredKeys.isEmpty()) {
            throw new IllegalStateException("nexus.api-key.hmac-keys must configure at least one key");
        }
        Map<Integer, SecretKeySpec> decoded = new HashMap<>();
        configuredKeys.forEach((version, value) -> {
            if (version == null || version <= 0) {
                throw new IllegalStateException("API key HMAC versions must be positive integers");
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("API key HMAC key v" + version + " must be valid Base64", exception);
            }
            if (raw.length != 32) {
                throw new IllegalStateException("API key HMAC key v" + version + " must decode to exactly 32 bytes");
            }
            decoded.put(version, new SecretKeySpec(raw, "HmacSHA256"));
        });
        return Map.copyOf(decoded);
    }
}
