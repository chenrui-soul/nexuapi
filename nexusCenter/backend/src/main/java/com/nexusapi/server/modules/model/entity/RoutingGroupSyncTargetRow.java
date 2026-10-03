package com.nexusapi.server.modules.model.entity;

import java.util.UUID;

/** 同步任务用于定位本地服务分组与上游来源身份的最小数据行。 */
public class RoutingGroupSyncTargetRow {
    /** 本地服务分组标识。 */
    private UUID id;
    /** 本地服务分组名称，仅用于首次无损绑定已有分组。 */
    private String name;
    /** 已绑定的上游分组 ID；人工分组为空。 */
    private String sourceGroupId;
    /** 已保存的上游白名单快照哈希。 */
    private String sourcePayloadHash;
    /** 当前上游可见状态，用于把 stale 恢复为 active 时计入更新。 */
    private String sourceStatus;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceGroupId() { return sourceGroupId; }
    public void setSourceGroupId(String sourceGroupId) { this.sourceGroupId = sourceGroupId; }
    public String getSourcePayloadHash() { return sourcePayloadHash; }
    public void setSourcePayloadHash(String sourcePayloadHash) { this.sourcePayloadHash = sourcePayloadHash; }
    public String getSourceStatus() { return sourceStatus; }
    public void setSourceStatus(String sourceStatus) { this.sourceStatus = sourceStatus; }
}
