package com.nexusapi.server.modules.apikey.enums;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;

import java.util.Locale;

public enum ApiKeyStatus {
    ACTIVE("active"),
    DISABLED("disabled"),
    EXPIRED("expired"),
    REVOKED("revoked");

    private final String value;

    ApiKeyStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ApiKeyStatus parseFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API 令牌状态筛选不正确", null);
        }
    }

    public static ApiKeyStatus parseMutable(String value) {
        ApiKeyStatus status = parseFilter(value);
        if (status != ACTIVE && status != DISABLED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "状态只能设置为 active 或 disabled", null);
        }
        return status;
    }
}
