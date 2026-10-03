package com.nexusapi.server.modules.channel.security;

import com.nexusapi.server.common.config.NexusProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 渠道凭证专用 AES-256-GCM 加密组件。
 *
 * <p>它从现有主密钥派生独立加密键和指纹键，并使用渠道专属 AAD，避免与用户字段密文跨域复用。</p>
 */
@Component
public class ChannelCredentialCipher {
    public static final int ACTIVE_KEY_VERSION = 1;
    private static final byte FORMAT_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final byte[] AAD = "nexus:channel-credential:v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec fingerprintKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ChannelCredentialCipher(NexusProperties properties) {
        byte[] masterKey = decodeMasterKey(properties.security().fieldEncryptionKey());
        this.encryptionKey = new SecretKeySpec(derive(masterKey, "encrypt"), "AES");
        this.fingerprintKey = new SecretKeySpec(derive(masterKey, "fingerprint"), "HmacSHA256");
    }

    public EncryptedCredential encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || plaintext.length() > 4_096
                || plaintext.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid channel credential");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(1 + IV_LENGTH + encrypted.length)
                    .put(FORMAT_VERSION)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return new EncryptedCredential(payload, ACTIVE_KEY_VERSION, fingerprint(plaintext));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to encrypt channel credential", exception);
        }
    }

    /** 供后续 Gateway 在请求上游前解密；管理接口不得调用后回显。 */
    public String decrypt(byte[] ciphertext, int keyVersion) {
        if (keyVersion != ACTIVE_KEY_VERSION || ciphertext == null || ciphertext.length <= 1 + IV_LENGTH) {
            throw new IllegalArgumentException("Unsupported channel credential payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
        if (buffer.get() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported channel credential format");
        }
        byte[] iv = new byte[IV_LENGTH];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to decrypt channel credential", exception);
        }
    }

    private String fingerprint(String plaintext) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(fingerprintKey);
        return HexFormat.of().formatHex(mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), 0, 8);
    }

    private byte[] derive(byte[] masterKey, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal(("nexus:channel-credential:" + purpose + ":v1")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to derive channel credential key", exception);
        }
    }

    private byte[] decodeMasterKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32) {
                throw new IllegalStateException("nexus.security.field-encryption-key must decode to 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("nexus.security.field-encryption-key must be valid Base64", exception);
        }
    }

    public record EncryptedCredential(byte[] ciphertext, int keyVersion, String fingerprint) {
        public EncryptedCredential {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
