package com.nexusapi.server.modules.auth.support;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

@Component
public class EmailNormalizer {
    public String normalize(String email) {
        String normalized = Normalizer.normalize(email.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || normalized.length() > 320) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请输入有效的邮箱地址", null);
        }
        return normalized;
    }
}
