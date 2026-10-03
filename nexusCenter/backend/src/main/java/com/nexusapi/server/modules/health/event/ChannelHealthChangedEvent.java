package com.nexusapi.server.modules.health.event;

import java.util.UUID;

/** 渠道健康事务提交后触发分组聚合和告警处理的最小事件，不携带错误原文或凭证。 */
public record ChannelHealthChangedEvent(UUID channelId) {
}
