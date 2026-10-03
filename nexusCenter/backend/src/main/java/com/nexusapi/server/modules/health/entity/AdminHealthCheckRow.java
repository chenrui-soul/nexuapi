package com.nexusapi.server.modules.health.entity;

import java.time.Instant;
import java.util.UUID;

/** 管理端健康历史数据库行；错误摘要只能来自后端固定分类或脱敏结果。 */
public class AdminHealthCheckRow {
    /** 健康历史自增主键。 */
    private long id;
    /** 目标类型：channel 或 group。 */
    private String targetType;
    /** 目标主键。 */
    private UUID targetId;
    /** 目标展示名称。 */
    private String targetName;
    /** 健康结论。 */
    private String status;
    /** 探测耗时；分组聚合没有网络耗时。 */
    private Integer latencyMs;
    /** 固定分类或脱敏摘要。 */
    private String errorSummary;
    /** 检查时间。 */
    private Instant checkedAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }
}
