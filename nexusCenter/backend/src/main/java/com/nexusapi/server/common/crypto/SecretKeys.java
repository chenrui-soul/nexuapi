package com.nexusapi.server.common.crypto;

import java.util.Base64;

final class SecretKeys {
    private SecretKeys() {
    }

    static byte[] decode32Bytes(String value, String propertyName) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32) {
                throw new IllegalStateException(propertyName + " must decode to exactly 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(propertyName + " must be valid Base64", exception);
        }
    }
}

