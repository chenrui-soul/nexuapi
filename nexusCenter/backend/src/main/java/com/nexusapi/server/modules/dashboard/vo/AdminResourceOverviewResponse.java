package com.nexusapi.server.modules.dashboard.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 管理端运营总览的资源真实数量，所有统计均由数据库直接聚合。 */
public record AdminResourceOverviewResponse(
        ResourceCount suppliers,
        ResourceCount models,
        ResourceCount channels,
        @JsonProperty("open_alert_count") long openAlertCount
) {
    /** 单类资源的总数量和启用数量。 */
    public record ResourceCount(long total, long active) {
    }
}
