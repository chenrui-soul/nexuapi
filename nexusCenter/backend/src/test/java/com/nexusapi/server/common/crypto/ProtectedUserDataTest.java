package com.nexusapi.server.common.crypto;

import com.nexusapi.server.common.config.NexusProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectedUserDataTest {

    private static final String ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String LOOKUP_KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

    @Test
    void encryptedEmailRoundTripsWithoutContainingPlaintext() {
        NexusProperties properties = properties();
        AesGcmFieldCipher cipher = new AesGcmFieldCipher(properties);
        String email = "security.user@example.com";

        byte[] encrypted = cipher.encrypt(email);

        assertThat(new String(encrypted, StandardCharsets.UTF_8)).doesNotContain(email);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(email);
        assertThat(cipher.encrypt(email)).isNotEqualTo(encrypted);
    }

    @Test
    void lookupHashesAreDeterministicAndNamespaced() {
        HmacLookupHasher hasher = new HmacLookupHasher(properties());

        assertThat(hasher.hash("user-email", "security.user@example.com"))
                .isEqualTo(hasher.hash("user-email", "security.user@example.com"))
                .isNotEqualTo(hasher.hash("captcha", "security.user@example.com"));
    }

    private NexusProperties properties() {
        return new NexusProperties(
                new NexusProperties.Security(List.of("http://localhost:3000"), ENCRYPTION_KEY, LOOKUP_KEY),
                new NexusProperties.Auth(
                        Duration.ofMinutes(5), 5, 30, 8, Duration.ofMinutes(15),
                        Duration.ofMinutes(30), Duration.ofDays(30), Duration.ofMinutes(10),
                        5, 5, false, "no-reply@nexus.local"
                ),
                new NexusProperties.ApiKey(1, Map.of(1, LOOKUP_KEY), 20, 5, 20),
                new NexusProperties.SystemAccessToken(1, Map.of(1, LOOKUP_KEY), 10),
                new NexusProperties.Gateway(
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(2),
                        10,
                        20,
                        2,
                        2048L,
                        8 * 1024 * 1024,
                        Duration.ofMinutes(30),
                        Duration.ofSeconds(30)
                )
        );
    }
}
