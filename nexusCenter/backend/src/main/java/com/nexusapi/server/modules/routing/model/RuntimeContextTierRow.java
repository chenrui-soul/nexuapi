package com.nexusapi.server.modules.routing.model;

import java.util.UUID;

/** Gateway 使用的长上下文分档快照；不包含任何上游凭证或请求正文。 */
public record RuntimeContextTierRow(
        /** 分档主键，用于请求计费快照溯源。 */
        UUID id,
        /** 稳定匹配优先级。 */
        int priority,
        /** 左闭右开的输入 Token 范围。 */
        long minInputTokens,
        Long maxInputTokens,
        /** 本档实际 Token 倍率，单位为万分位。 */
        long inputRatio,
        long outputRatio,
        long cachedInputRatio,
        /** 本档缓存写入倍率；当前先进入运行时快照，为后续缓存用量结算保留完整契约。 */
        long cacheWrite5mRatio,
        long cacheWrite1hRatio
) {
}
