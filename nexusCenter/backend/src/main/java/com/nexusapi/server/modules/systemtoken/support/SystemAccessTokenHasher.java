package com.nexusapi.server.modules.systemtoken.support;

import com.nexusapi.server.common.config.NexusProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** 使用系统访问令牌专用、可版本化的 HMAC-SHA256 密钥生成不可逆摘要。 */
@Component
public class SystemAccessTokenHasher {
    private final int activeVersion;
    private final Map<Integer, SecretKeySpec> keys;

    public SystemAccessTokenHasher(NexusProperties properties) {
        NexusProperties.SystemAccessToken configuration = properties.systemAccessToken();
        this.activeVersion = configuration.activeHashVersion();
        this.keys = decode(configuration.hmacKeys());
        if (!keys.containsKey(activeVersion)) {
            throw new IllegalStateException("nexus.system-access-token.active-hash-version must reference a configured HMAC key");
        }
    }

    public int activeVersion() { return activeVersion; }
    public boolean supports(int version) { return keys.containsKey(version); }

    public byte[] hash(int version, String secret) {
        SecretKeySpec key = keys.get(version);
        if (key == null) throw new IllegalArgumentException("Unsupported system access token hash version");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(("system-access-token:v" + version + ':' + secret)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create system access token hash", exception);
        }
    }

    private Map<Integer, SecretKeySpec> decode(Map<Integer, String> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("nexus.system-access-token.hmac-keys must configure at least one key");
        }
        Map<Integer, SecretKeySpec> result = new HashMap<>();
        configured.forEach((version, encoded) -> {
            if (version == null || version <= 0) throw new IllegalStateException("System token HMAC versions must be positive");
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("System token HMAC key must be valid Base64", exception);
            }
            if (raw.length != 32) throw new IllegalStateException("System token HMAC key must decode to 32 bytes");
            result.put(version, new SecretKeySpec(raw, "HmacSHA256"));
        });
        return Map.copyOf(result);
    }
}
