package com.nexusapi.server.modules.model.entity;

import java.time.Instant;
import java.util.UUID;

/** 模型市场一次同步运行的脱敏数据库记录。 */
public class ModelSyncRunRow {
    /** 同步运行全局唯一标识。 */
    private UUID id;
    /** 触发方式：manual 或 scheduled。 */
    private String triggerType;
    /** 手动执行管理员标识；定时执行时为空。 */
    private UUID actorUserId;
    /** 运行状态：running、succeeded 或 failed。 */
    private String status;
    /** 上游声明的模型总数。 */
    private Integer upstreamTotal;
    /** 成功拉取并校验的模型数量。 */
    private int fetchedCount;
    /** 安全新增模型数量。 */
    private int insertedCount;
    /** 来源快照或托管基础字段更新数量。 */
    private int updatedCount;
    /** 上游内容未变化的模型数量。 */
    private int unchangedCount;
    /** 大小写冲突或非法模型跳过数量。 */
    private int skippedCount;
    /** 本轮上游可见服务分组总数。 */
    private int groupTotal;
    /** 本轮新建服务分组数量。 */
    private int groupInsertedCount;
    /** 本轮上游信息变化或首次绑定来源的服务分组数量。 */
    private int groupUpdatedCount;
    /** 本轮内容未变化的服务分组数量。 */
    private int groupUnchangedCount;
    /** 本轮从 active 转为 stale 的服务分组数量。 */
    private int groupStaleCount;
    /** 固定机器错误分类。 */
    private String errorCode;
    /** 不包含上游正文和凭证的错误摘要。 */
    private String errorSummary;
    /** 同步开始时间。 */
    private Instant startedAt;
    /** 同步完成或失败时间。 */
    private Instant completedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getUpstreamTotal() { return upstreamTotal; }
    public void setUpstreamTotal(Integer upstreamTotal) { this.upstreamTotal = upstreamTotal; }
    public int getFetchedCount() { return fetchedCount; }
    public void setFetchedCount(int fetchedCount) { this.fetchedCount = fetchedCount; }
    public int getInsertedCount() { return insertedCount; }
    public void setInsertedCount(int insertedCount) { this.insertedCount = insertedCount; }
    public int getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(int updatedCount) { this.updatedCount = updatedCount; }
    public int getUnchangedCount() { return unchangedCount; }
    public void setUnchangedCount(int unchangedCount) { this.unchangedCount = unchangedCount; }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
    public int getGroupTotal() { return groupTotal; }
    public void setGroupTotal(int groupTotal) { this.groupTotal = groupTotal; }
    public int getGroupInsertedCount() { return groupInsertedCount; }
    public void setGroupInsertedCount(int groupInsertedCount) { this.groupInsertedCount = groupInsertedCount; }
    public int getGroupUpdatedCount() { return groupUpdatedCount; }
    public void setGroupUpdatedCount(int groupUpdatedCount) { this.groupUpdatedCount = groupUpdatedCount; }
    public int getGroupUnchangedCount() { return groupUnchangedCount; }
    public void setGroupUnchangedCount(int groupUnchangedCount) { this.groupUnchangedCount = groupUnchangedCount; }
    public int getGroupStaleCount() { return groupStaleCount; }
    public void setGroupStaleCount(int groupStaleCount) { this.groupStaleCount = groupStaleCount; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
