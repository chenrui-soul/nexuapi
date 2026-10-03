package com.nexusapi.server.modules.subscription.dto;

/** 用户取消当前订阅的请求；版本号用于避免覆盖并发状态变化。 */
public record SubscriptionCancelRequest(long version) { }
