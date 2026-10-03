package com.nexusapi.server.modules.channel;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelCredentialCipherTest {

    @Test
    void tamperedCiphertextCannotBeDecrypted() {
        String testMasterKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
        );
        NexusProperties properties = new NexusProperties(
                new NexusProperties.Security(java.util.List.of(), testMasterKey, testMasterKey),
                null,
                null,
                null,
                null
        );
        ChannelCredentialCipher cipher = new ChannelCredentialCipher(properties);
        ChannelCredentialCipher.EncryptedCredential encrypted = cipher.encrypt("test-upstream-secret");
        byte[] tampered = encrypted.ciphertext();
        tampered[tampered.length - 1] ^= 0x01;

        // AES-GCM 必须拒绝任何密文篡改，不能返回部分明文或静默降级。
        assertThatThrownBy(() -> cipher.decrypt(tampered, encrypted.keyVersion()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt channel credential");
    }
}
