package com.nexusapi.server.modules.auth.service;

public interface CaptchaCodeGenerator {
    String generate(int length);
}
