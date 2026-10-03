package com.nexusapi.server.modules.model.entity;

import java.time.Instant;
import java.util.UUID;

/** 经过分组、模型和健康状态交叉校验的分组模型关联。 */
public class SyncedGroupModelRow {
    /** 新建关联时使用的全局唯一标识。 */
    private UUID id;
    /** 本地服务分组标识。 */
    private UUID groupId;
    /** 本地平台模型标识。 */
    private UUID modelId;
    /** 上游最后健康状态原始值；未返回时为空。 */
    private Integer upstreamLastStatus;
    /** 上游成功率原始整数，10000 表示 100%。 */
    private Integer upstreamSuccessRate;
    /** 上游连续失败次数。 */
    private int upstreamConsecutiveFailures;
    /** 仅含 0/1 状态值的脱敏健康历史 JSON 数组。 */
    private String upstreamHealthHistoryJson;
    /** 上游最近健康检查时间。 */
    private Instant upstreamLastCheckedAt;
    /** 上游最近健康成功时间。 */
    private Instant upstreamLastSuccessAt;
    /** 本轮完整快照发现该关联的时间。 */
    private Instant seenAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }
    public UUID getModelId() { return modelId; }
    public void setModelId(UUID modelId) { this.modelId = modelId; }
    public Integer getUpstreamLastStatus() { return upstreamLastStatus; }
    public void setUpstreamLastStatus(Integer upstreamLastStatus) { this.upstreamLastStatus = upstreamLastStatus; }
    public Integer getUpstreamSuccessRate() { return upstreamSuccessRate; }
    public void setUpstreamSuccessRate(Integer upstreamSuccessRate) { this.upstreamSuccessRate = upstreamSuccessRate; }
    public int getUpstreamConsecutiveFailures() { return upstreamConsecutiveFailures; }
    public void setUpstreamConsecutiveFailures(int upstreamConsecutiveFailures) { this.upstreamConsecutiveFailures = upstreamConsecutiveFailures; }
    public String getUpstreamHealthHistoryJson() { return upstreamHealthHistoryJson; }
    public void setUpstreamHealthHistoryJson(String upstreamHealthHistoryJson) { this.upstreamHealthHistoryJson = upstreamHealthHistoryJson; }
    public Instant getUpstreamLastCheckedAt() { return upstreamLastCheckedAt; }
    public void setUpstreamLastCheckedAt(Instant upstreamLastCheckedAt) { this.upstreamLastCheckedAt = upstreamLastCheckedAt; }
    public Instant getUpstreamLastSuccessAt() { return upstreamLastSuccessAt; }
    public void setUpstreamLastSuccessAt(Instant upstreamLastSuccessAt) { this.upstreamLastSuccessAt = upstreamLastSuccessAt; }
    public Instant getSeenAt() { return seenAt; }
    public void setSeenAt(Instant seenAt) { this.seenAt = seenAt; }
}
