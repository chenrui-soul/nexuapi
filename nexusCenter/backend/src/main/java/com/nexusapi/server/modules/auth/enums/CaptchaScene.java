package com.nexusapi.server.modules.auth.enums;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;

import java.util.Locale;

public enum CaptchaScene {
    LOGIN("login"),
    REGISTER("register"),
    PASSWORD_RESET("password_reset");

    private final String value;

    CaptchaScene(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CaptchaScene parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (CaptchaScene scene : values()) {
            if (scene.value.equals(normalized)) {
                return scene;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "验证码场景不正确", null);
    }
}
