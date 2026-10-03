package com.nexusapi.server.common.crypto;

import com.nexusapi.server.common.config.NexusProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

@Component
public class AesGcmFieldCipher {
    private static final byte FORMAT_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final byte[] AAD = "nexus:user-field:v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmFieldCipher(NexusProperties properties) {
        byte[] rawKey = SecretKeys.decode32Bytes(
                properties.security().fieldEncryptionKey(),
                "nexus.security.field-encryption-key"
        );
        this.key = new SecretKeySpec(rawKey, "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_LENGTH + encrypted.length)
                    .put(FORMAT_VERSION)
                    .put(iv)
                    .put(encrypted)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to encrypt protected field", exception);
        }
    }

    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length <= 1 + IV_LENGTH) {
            throw new IllegalArgumentException("Invalid protected field payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
        byte version = buffer.get();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported protected field version");
        }
        byte[] iv = new byte[IV_LENGTH];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to decrypt protected field", exception);
        }
    }
}

