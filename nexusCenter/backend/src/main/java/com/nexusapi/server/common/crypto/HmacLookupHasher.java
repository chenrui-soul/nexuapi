package com.nexusapi.server.common.crypto;

import com.nexusapi.server.common.config.NexusProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public class HmacLookupHasher {
    private final SecretKeySpec key;

    public HmacLookupHasher(NexusProperties properties) {
        byte[] rawKey = SecretKeys.decode32Bytes(
                properties.security().lookupHmacKey(),
                "nexus.security.lookup-hmac-key"
        );
        this.key = new SecretKeySpec(rawKey, "HmacSHA256");
    }

    public byte[] hash(String namespace, String normalizedValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal((namespace + ':' + normalizedValue).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create protected lookup hash", exception);
        }
    }

    public String hashHex(String namespace, String normalizedValue) {
        return HexFormat.of().formatHex(hash(namespace, normalizedValue));
    }
}
