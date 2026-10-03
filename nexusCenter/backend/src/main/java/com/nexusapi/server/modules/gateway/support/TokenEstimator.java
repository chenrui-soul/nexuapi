package com.nexusapi.server.modules.gateway.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.routing.model.RuntimeModelRow;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 在不绑定特定 tokenizer 的前提下，用偏保守估算完成 TPM 占位和资金预冻结。 */
@Component
public class TokenEstimator {
    private final ObjectMapper objectMapper;

    public TokenEstimator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** UTF-8 每 3 字节按一个 Token 估算，并增加协议固定余量，兼顾英文和中文输入。 */
    public long estimateInputTokens(ObjectNode request) {
        try {
            int bytes = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8).length;
            return Math.max(1L, (bytes + 2L) / 3L + 32L);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请求 JSON 无法序列化", null);
        }
    }

    public long resolveMaxOutputTokens(ObjectNode request, RuntimeModelRow model, long configuredDefault) {
        long modelLimit = model.getMaxOutputTokens() == null
                ? Long.MAX_VALUE : model.getMaxOutputTokens();
        long requested = readPositiveLong(request, "max_completion_tokens");
        if (requested == 0L) {
            requested = readPositiveLong(request, "max_tokens");
        }
        if (requested == 0L) {
            requested = Math.min(Math.max(1L, configuredDefault), modelLimit);
        }
        if (requested > modelLimit) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请求的最大输出 Token 超过模型上限", null);
        }
        return requested;
    }

    public long estimateTextTokens(long utf16Characters) {
        return Math.max(0L, (utf16Characters + 2L) / 3L);
    }

    private long readPositiveLong(ObjectNode request, String field) {
        if (!request.has(field) || request.get(field).isNull()) {
            return 0L;
        }
        if (!request.get(field).canConvertToLong()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + " 必须是正整数", null);
        }
        long value = request.get(field).longValue();
        if (value <= 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + " 必须是正整数", null);
        }
        return value;
    }
}
