package com.nexusapi.server.modules.subscription.entity;

import java.util.UUID;

/** 活动订阅运行时权限快照；只返回 Gateway 需要的订阅标识和并发上限。 */
public class SubscriptionAccessRow {
    /** 活动订阅记录主键。 */
    private UUID subscriptionId;
    /** 订阅开通时快照的并发上限。 */
    private Integer concurrencyLimit;

    public UUID getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(UUID subscriptionId) { this.subscriptionId = subscriptionId; }
    public Integer getConcurrencyLimit() { return concurrencyLimit; }
    public void setConcurrencyLimit(Integer concurrencyLimit) { this.concurrencyLimit = concurrencyLimit; }
}
