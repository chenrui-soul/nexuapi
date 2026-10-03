package com.nexusapi.server.modules.model.pricing.model;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;

import java.util.Locale;

/** 平台统一计费类型；能力类型只描述模型能力，不能代替计费类型。 */
public enum BillingType {
    REQUEST(1, "request"),
    QUANTITY(2, "quantity"),
    VIDEO_SECOND(3, "video_second"),
    TOKEN(4, "million_tokens"),
    CHARACTER(5, "million_characters"),
    AUDIO_SECOND(6, "audio_second");

    private final int code;
    private final String unit;

    BillingType(int code, String unit) {
        this.code = code;
        this.unit = unit;
    }

    public int code() {
        return code;
    }

    public String unit() {
        return unit;
    }

    public static BillingType fromCode(int code) {
        for (BillingType value : values()) {
            if (value.code == code) return value;
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不支持的计费类型", null);
    }

    /** 校验管理员选择的计费类型是否适合当前模型能力。 */
    public boolean supportsCapability(String capabilityType) {
        String capability = capabilityType == null ? "" : capabilityType.toLowerCase(Locale.ROOT);
        return switch (capability) {
            // VIDEO_SECOND 仅为历史音频价格版本兼容；新音频按秒版本必须使用 AUDIO_SECOND。
            case "audio" -> this == REQUEST || this == VIDEO_SECOND || this == CHARACTER || this == AUDIO_SECOND;
            case "image" -> this == REQUEST || this == QUANTITY;
            case "video" -> this == REQUEST || this == VIDEO_SECOND;
            case "text", "embedding", "multimodal" -> this == REQUEST || this == TOKEN;
            default -> false;
        };
    }
}
