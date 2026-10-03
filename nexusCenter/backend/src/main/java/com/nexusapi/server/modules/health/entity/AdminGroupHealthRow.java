package com.nexusapi.server.modules.health.entity;

import java.time.Instant;
import java.util.UUID;

/** 管理端路由分组健康聚合数据库行。 */
public class AdminGroupHealthRow extends GroupHealthStateRow {
    /** 最近一次状态转换被记录的时间。 */
    private Instant latestCheckedAt;

    public Instant getLatestCheckedAt() { return latestCheckedAt; }
    public void setLatestCheckedAt(Instant latestCheckedAt) { this.latestCheckedAt = latestCheckedAt; }
}
