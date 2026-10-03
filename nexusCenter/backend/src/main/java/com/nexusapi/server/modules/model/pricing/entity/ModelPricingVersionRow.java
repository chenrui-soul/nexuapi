package com.nexusapi.server.modules.model.pricing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 不可变的平台销售价格版本；来源字段用于区分管理员售价和上游同步售价。 */
public record ModelPricingVersionRow(
        /** 价格版本主键。 */
        UUID id,
        /** 所属平台模型。 */
        UUID modelId,
        /** 模型内单调递增的版本号。 */
        long versionNo,
        /** 计费类型：按次、图片张数、视频秒、Token、字符或音频秒。 */
        int billingType,
        /** 平台销售基准积分单价。 */
        BigDecimal unitPrice,
        /** 只用于划线展示、不参与扣费的原价。 */
        BigDecimal displayOriginalPrice,
        /** Token 计费倍率，单位为万分位。 */
        long inputTokenRatio,
        long outputTokenRatio,
        long audioInputTokenRatio,
        long audioOutputTokenRatio,
        long cachedInputTokenRatio,
        /** 缓存写入倍率，0 表示使用平台默认推导规则。 */
        long cacheWrite5mTokenRatio,
        long cacheWrite1hTokenRatio,
        /** 展示给管理员和用户的计费说明。 */
        String chargeDesc,
        /** 长上下文分档模式。 */
        int contextTierMode,
        /** 条件规则未命中时使用基础价或拒绝。 */
        String unmatchedBehavior,
        /** 版本来源：manual 或受信任的上游同步来源。 */
        String sourceType,
        /** 上游有效价格快照 SHA-256；人工版本为空。 */
        String sourceHash,
        /** 上游快照被完整观测到的时间；人工版本为空。 */
        Instant sourceObservedAt,
        /** 版本变更说明。 */
        String changeNote,
        /** 发布管理员；自动同步版本为空。 */
        UUID createdBy,
        /** 版本创建时间。 */
        Instant createdAt
) {
}
