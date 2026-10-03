package com.nexusapi.server.modules.dashboard.entity;

import java.math.BigDecimal;

/** 管理端经营总览的精确事实汇总数据库行。 */
public class DashboardSummaryRow {
    /** 统计区间内最终请求总数。 */
    private long requestCount;
    /** 统计区间内最终成功请求数。 */
    private long successCount;
    /** 统计区间内最终失败请求数。 */
    private long failureCount;
    /** 统计区间内归责供应商的最终失败请求数。 */
    private long supplierFailureCount;
    /** 统计区间内输入 Token 总数。 */
    private long inputTokens;
    /** 统计区间内输出 Token 总数。 */
    private long outputTokens;
    /** 统计区间内缓存输入 Token 总数。 */
    private long cachedTokens;
    /** 平台实际结算收入，保持 NUMERIC(20,8) 精度。 */
    private BigDecimal billedAmount;
    /** 供应商实际成本，保持 NUMERIC(20,8) 精度。 */
    private BigDecimal supplierCostAmount;
    /** 平台实际毛利，允许为负数。 */
    private BigDecimal grossMarginAmount;
    /** 最终请求耗时总和，单位毫秒。 */
    private long latencySumMs;
    /** 最终请求完整区间延迟 P95，单位毫秒。 */
    private Long latencyP95Ms;
    /** 真实上游调用尝试总数。 */
    private long attemptCount;
    /** 真实上游调用成功次数。 */
    private long attemptSuccessCount;
    /** 归责供应商的真实上游失败尝试次数。 */
    private long attemptSupplierFailureCount;
    /** 真实上游调用尝试耗时总和，单位毫秒。 */
    private long attemptLatencySumMs;
    /** 真实上游调用尝试完整区间延迟 P95，单位毫秒。 */
    private Long attemptLatencyP95Ms;

    public long getRequestCount() { return requestCount; }
    public void setRequestCount(long requestCount) { this.requestCount = requestCount; }
    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }
    public long getFailureCount() { return failureCount; }
    public void setFailureCount(long failureCount) { this.failureCount = failureCount; }
    public long getSupplierFailureCount() { return supplierFailureCount; }
    public void setSupplierFailureCount(long supplierFailureCount) { this.supplierFailureCount = supplierFailureCount; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(long cachedTokens) { this.cachedTokens = cachedTokens; }
    public BigDecimal getBilledAmount() { return billedAmount; }
    public void setBilledAmount(BigDecimal billedAmount) { this.billedAmount = billedAmount; }
    public BigDecimal getSupplierCostAmount() { return supplierCostAmount; }
    public void setSupplierCostAmount(BigDecimal supplierCostAmount) { this.supplierCostAmount = supplierCostAmount; }
    public BigDecimal getGrossMarginAmount() { return grossMarginAmount; }
    public void setGrossMarginAmount(BigDecimal grossMarginAmount) { this.grossMarginAmount = grossMarginAmount; }
    public long getLatencySumMs() { return latencySumMs; }
    public void setLatencySumMs(long latencySumMs) { this.latencySumMs = latencySumMs; }
    public Long getLatencyP95Ms() { return latencyP95Ms; }
    public void setLatencyP95Ms(Long latencyP95Ms) { this.latencyP95Ms = latencyP95Ms; }
    public long getAttemptCount() { return attemptCount; }
    public void setAttemptCount(long attemptCount) { this.attemptCount = attemptCount; }
    public long getAttemptSuccessCount() { return attemptSuccessCount; }
    public void setAttemptSuccessCount(long attemptSuccessCount) { this.attemptSuccessCount = attemptSuccessCount; }
    public long getAttemptSupplierFailureCount() { return attemptSupplierFailureCount; }
    public void setAttemptSupplierFailureCount(long value) { this.attemptSupplierFailureCount = value; }
    public long getAttemptLatencySumMs() { return attemptLatencySumMs; }
    public void setAttemptLatencySumMs(long attemptLatencySumMs) { this.attemptLatencySumMs = attemptLatencySumMs; }
    public Long getAttemptLatencyP95Ms() { return attemptLatencyP95Ms; }
    public void setAttemptLatencyP95Ms(Long attemptLatencyP95Ms) { this.attemptLatencyP95Ms = attemptLatencyP95Ms; }
}

