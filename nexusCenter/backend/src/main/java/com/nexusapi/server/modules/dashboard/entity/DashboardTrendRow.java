package com.nexusapi.server.modules.dashboard.entity;

import java.math.BigDecimal;
import java.time.Instant;

/** 管理端经营趋势的单个时间桶数据库行。 */
public class DashboardTrendRow {
    /** 按 Asia/Shanghai 对齐后转换为 TIMESTAMPTZ 的时间桶起点。 */
    private Instant bucketStart;
    /** 时间桶内最终请求总数。 */
    private long requestCount;
    /** 时间桶内最终成功请求数。 */
    private long successCount;
    /** 时间桶内最终失败请求数。 */
    private long failureCount;
    /** 时间桶内平台实际结算收入。 */
    private BigDecimal billedAmount;
    /** 时间桶内供应商实际成本。 */
    private BigDecimal supplierCostAmount;
    /** 时间桶内平台实际毛利。 */
    private BigDecimal grossMarginAmount;
    /** 时间桶内最终请求延迟 P95，单位毫秒。 */
    private Long latencyP95Ms;

    public Instant getBucketStart() { return bucketStart; }
    public void setBucketStart(Instant bucketStart) { this.bucketStart = bucketStart; }
    public long getRequestCount() { return requestCount; }
    public void setRequestCount(long requestCount) { this.requestCount = requestCount; }
    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }
    public long getFailureCount() { return failureCount; }
    public void setFailureCount(long failureCount) { this.failureCount = failureCount; }
    public BigDecimal getBilledAmount() { return billedAmount; }
    public void setBilledAmount(BigDecimal billedAmount) { this.billedAmount = billedAmount; }
    public BigDecimal getSupplierCostAmount() { return supplierCostAmount; }
    public void setSupplierCostAmount(BigDecimal supplierCostAmount) { this.supplierCostAmount = supplierCostAmount; }
    public BigDecimal getGrossMarginAmount() { return grossMarginAmount; }
    public void setGrossMarginAmount(BigDecimal grossMarginAmount) { this.grossMarginAmount = grossMarginAmount; }
    public Long getLatencyP95Ms() { return latencyP95Ms; }
    public void setLatencyP95Ms(Long latencyP95Ms) { this.latencyP95Ms = latencyP95Ms; }
}

