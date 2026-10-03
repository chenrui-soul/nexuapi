package com.nexusapi.server.modules.model.pricing.entity;

import java.util.UUID;

/** 长上下文分档；倍率是该档实际采用的绝对万分位值，maxInputTokens 为空表示无上限。 */
public record ModelContextTierRow(
        /** 分档主键。 */
        UUID id,
        /** 所属不可变价格版本。 */
        UUID pricingVersionId,
        /** 同起点分档的稳定匹配顺序。 */
        int priority,
        /** 包含的最小输入 Token 数。 */
        long minInputTokens,
        /** 不包含的最大输入 Token 数；为空表示无上限。 */
        Long maxInputTokens,
        /** 本分档实际输入、输出和缓存输入倍率，单位为万分位。 */
        long inputRatio,
        long outputRatio,
        long cachedInputRatio,
        /** 本分档缓存写入倍率；0 表示沿用版本级默认规则。 */
        long cacheWrite5mRatio,
        long cacheWrite1hRatio
) {
}
