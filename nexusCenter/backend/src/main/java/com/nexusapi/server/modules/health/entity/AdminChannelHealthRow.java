package com.nexusapi.server.modules.health.entity;

import java.time.Instant;
import java.util.UUID;

/** 管理端渠道健康列表数据库行，不包含上游地址、凭证密文或 Authorization。 */
public class AdminChannelHealthRow {
    /** 渠道主键。 */
    private UUID channelId;
    /** 渠道展示名称。 */
    private String channelName;
    /** 所属供应商展示名称。 */
    private String supplierName;
    /** 当前渠道状态。 */
    private String channelStatus;
    /** 受控健康探测相对路径。 */
    private String healthProbePath;
    /** 连续失败次数。 */
    private int consecutiveFailures;
    /** 历史熔断截止时间兼容值，当前版本不参与路由。 */
    private Instant circuitOpenUntil;
    /** 最近一次主动或手动探测结论。 */
    private String latestCheckStatus;
    /** 最近一次探测耗时。 */
    private Integer latestLatencyMs;
    /** 最近一次固定分类摘要。 */
    private String latestErrorSummary;
    /** 最近一次探测时间。 */
    private Instant latestCheckedAt;

    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getChannelStatus() { return channelStatus; }
    public void setChannelStatus(String channelStatus) { this.channelStatus = channelStatus; }
    public String getHealthProbePath() { return healthProbePath; }
    public void setHealthProbePath(String healthProbePath) { this.healthProbePath = healthProbePath; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Instant getCircuitOpenUntil() { return circuitOpenUntil; }
    public void setCircuitOpenUntil(Instant circuitOpenUntil) { this.circuitOpenUntil = circuitOpenUntil; }
    public String getLatestCheckStatus() { return latestCheckStatus; }
    public void setLatestCheckStatus(String latestCheckStatus) { this.latestCheckStatus = latestCheckStatus; }
    public Integer getLatestLatencyMs() { return latestLatencyMs; }
    public void setLatestLatencyMs(Integer latestLatencyMs) { this.latestLatencyMs = latestLatencyMs; }
    public String getLatestErrorSummary() { return latestErrorSummary; }
    public void setLatestErrorSummary(String latestErrorSummary) { this.latestErrorSummary = latestErrorSummary; }
    public Instant getLatestCheckedAt() { return latestCheckedAt; }
    public void setLatestCheckedAt(Instant latestCheckedAt) { this.latestCheckedAt = latestCheckedAt; }
}
