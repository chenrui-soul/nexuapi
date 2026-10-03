package com.nexusapi.server.modules.model.pricing.entity;

import java.time.Instant;
import java.util.UUID;

/** 发布或切换价格模式前锁定的模型最小状态。 */
public record PricingModelStateRow(
        /** 平台模型主键。 */
        UUID id,
        /** 用于限制合法计费类型的模型能力。 */
        String capabilityType,
        /** 当前生效价格版本。 */
        UUID activePricingVersionId,
        /** true 表示同步任务可以创建并激活新的上游价格版本。 */
        boolean pricingSourceManaged,
        /** 最近激活的上游价格快照摘要。 */
        String pricingSourceHash,
        /** 最近激活上游价格版本的时间。 */
        Instant pricingSourceSyncedAt,
        /** 模型乐观锁版本。 */
        long version
) {
}
