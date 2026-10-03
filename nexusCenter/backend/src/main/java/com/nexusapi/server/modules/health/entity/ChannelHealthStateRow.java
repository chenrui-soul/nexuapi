package com.nexusapi.server.modules.health.entity;

import java.time.Instant;
import java.util.UUID;

/** 渠道健康状态更新后的数据库快照，用于决定探测明细和供应商聚合结果。 */
public class ChannelHealthStateRow {
    /** 渠道主键。 */
    private UUID channelId;
    /** 渠道所属供应商主键。 */
    private UUID supplierId;
    /** 渠道当前状态。 */
    private String status;
    /** 当前连续失败次数。 */
    private int consecutiveFailures;
    /** 历史熔断截止时间兼容值，当前版本不参与路由。 */
    private Instant circuitOpenUntil;

    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public UUID getSupplierId() { return supplierId; }
    public void setSupplierId(UUID supplierId) { this.supplierId = supplierId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Instant getCircuitOpenUntil() { return circuitOpenUntil; }
    public void setCircuitOpenUntil(Instant circuitOpenUntil) { this.circuitOpenUntil = circuitOpenUntil; }
}
