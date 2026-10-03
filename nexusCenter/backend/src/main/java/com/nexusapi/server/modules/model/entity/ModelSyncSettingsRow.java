package com.nexusapi.server.modules.model.entity;

import java.time.Instant;
import java.util.UUID;

/** 模型市场定时同步的数据库单例配置。 */
public class ModelSyncSettingsRow {
    /** 固定为 1 的配置标识。 */
    private short id;
    /** 是否允许调度器自动执行。 */
    private boolean enabled;
    /** 自动同步周期，单位为分钟。 */
    private int intervalMinutes;
    /** 下一次计划执行时间；关闭时为空。 */
    private Instant nextRunAt;
    /** 最近修改配置的管理员标识。 */
    private UUID updatedBy;
    /** 管理员更新使用的乐观锁版本。 */
    private long version;
    /** 配置创建时间。 */
    private Instant createdAt;
    /** 配置最后人工更新时间。 */
    private Instant updatedAt;

    public short getId() { return id; }
    public void setId(short id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getIntervalMinutes() { return intervalMinutes; }
    public void setIntervalMinutes(int intervalMinutes) { this.intervalMinutes = intervalMinutes; }
    public Instant getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
