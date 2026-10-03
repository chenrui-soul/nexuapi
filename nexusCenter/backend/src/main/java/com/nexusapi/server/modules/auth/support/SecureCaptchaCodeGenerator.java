package com.nexusapi.server.modules.auth.support;

import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class SecureCaptchaCodeGenerator implements CaptchaCodeGenerator {
    private static final char[] ALPHABET = "23456789ACEFHJKLMNPRTUVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return result.toString();
    }
}
